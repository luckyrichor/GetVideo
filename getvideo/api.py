"""HTTP 接口。

手机端只跟这一层打交道：
    POST /extract    提交分享文本，返回视频记录
    GET  /tracks     浏览视频库
    GET  /sync       增量同步（手机端按 cursor 拉）
    GET  /video/{id} 下载视频文件
"""
from __future__ import annotations

import logging
import json
from datetime import timezone
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, HTTPException, Query
from fastapi.responses import FileResponse, Response, JSONResponse
from fastapi.exceptions import RequestValidationError
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.orm import Session

from . import agent, credentials, login
from . import accounts, extraction_agent, creator_worker
from .config import settings
from .db import SessionLocal, init_db
from .extractors import ExtractorError, NeedsLoginError
from .models import AgentMemory, Track
from .pipeline import extract as run_extract
from .sharelink import LinkError

log = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(_: FastAPI):
    init_db()
    settings.ensure_dirs()
    creator_worker.start()
    try:
        yield
    finally:
        creator_worker.stop()


class PlatformCallbackAccessFilter(logging.Filter):
    def filter(self, record):
        # Uvicorn access records contain the request target at args[2].
        if isinstance(record.args, tuple) and len(record.args) == 5:
            args = list(record.args)
            if isinstance(args[2], str) and args[2].split("?", 1)[0] == "/account/platform-auth/douyin/callback":
                args[2] = args[2].split("?", 1)[0]
                record.args = tuple(args)
        return True


logging.getLogger("uvicorn.access").addFilter(PlatformCallbackAccessFilter())

app = FastAPI(title="GetVideo", version="0.2.0", lifespan=lifespan,
              dependencies=[Depends(accounts.protect_account_ids)])
app.include_router(accounts.router)
app.include_router(extraction_agent.router)


@app.exception_handler(RequestValidationError)
async def validation_error(_, exc: RequestValidationError):
    # FastAPI's default error includes raw input (e.g. a too-short password).
    return JSONResponse(status_code=422, content={"detail": [
        {"loc": list(e["loc"]), "msg": e["msg"], "type": e["type"]} for e in exc.errors()]})


def get_session():
    s = SessionLocal()
    try:
        yield s
    finally:
        s.close()


# ---------- schema ----------


class ExtractRequest(BaseModel):
    text: str = Field(..., description="分享文本或链接，可以带平台的那堆废话")
    user_id: str | None = Field(None, description="用哪个用户的登录凭证")
    force: bool = Field(False, description="忽略去重，强制重新下载")


class TrackOut(BaseModel):
    id: int
    platform: str
    video_id: str
    title: str
    uploader: str | None
    duration_sec: float | None
    audio_bytes: int | None
    audio_format: str
    audio_sha256: str | None = None
    source_url: str
    updated_seq: int
    updated_at_ms: int
    deleted: bool
    categories: list[str] = Field(default_factory=list)
    has_vocal: bool | None = None
    classify_stage: str | None = None
    download_url: str

    @classmethod
    def of(cls, t: Track) -> "TrackOut":
        return cls(
            categories=json.loads(t.categories_json or "[]"),
            has_vocal=None if t.has_vocal is None else bool(t.has_vocal),
            classify_stage=t.classify_stage,
            id=t.id,
            platform=t.platform,
            video_id=t.video_id,
            title=t.title,
            uploader=t.uploader,
            duration_sec=t.duration_sec,
            audio_bytes=t.audio_bytes,
            audio_format=t.audio_format,
            audio_sha256=t.audio_sha256,
            source_url=t.source_url,
            updated_seq=t.updated_seq,
            updated_at_ms=int(t.updated_at.replace(tzinfo=timezone.utc).timestamp() * 1000)
                if t.updated_at.tzinfo is None else int(t.updated_at.timestamp() * 1000),
            deleted=bool(t.deleted),
            download_url=f"/video/{t.id}",
        )


class ExtractResponse(BaseModel):
    track: TrackOut
    reused: bool


class LoginStartRequest(BaseModel):
    user_id: str = Field(..., description="谁在登录")
    platform: str = Field(..., description="bilibili | douyin")


class LoginStartResponse(BaseModel):
    session_id: str
    platform: str
    qr_url: str = Field(..., description="把这段内容渲染成二维码让用户扫")
    qr_image_url: str = Field(..., description="服务端直接渲染好的 PNG，省得客户端自己画")
    expires_in: int


class LoginPollResponse(BaseModel):
    session_id: str
    platform: str
    state: str = Field(..., description="waiting | scanned | confirmed | expired | failed")
    message: str


class CookieImportRequest(BaseModel):
    user_id: str
    platform: str
    cookies: str = Field(..., description="浏览器里复制的 `a=1; b=2` 形式")


class CredentialOut(BaseModel):
    platform: str
    status: str
    last_checked_at: str | None


class SyncResponse(BaseModel):
    items: list[TrackOut]
    next_cursor: int
    has_more: bool


class AgentLocalTrack(BaseModel):
    id: int = Field(lt=0)
    title: str = Field(min_length=1, max_length=200)
    artist: str | None = Field(None, max_length=100)
    platform: str = Field(max_length=32)
    duration_sec: float | None = None
    categories: list[str] = Field(default_factory=list, max_length=6)


class AgentChatRequest(BaseModel):
    user_id: str = Field(min_length=1, max_length=64)
    message: str = Field(min_length=1, max_length=1000)
    local_tracks: list[AgentLocalTrack] = Field(default_factory=list, max_length=100)


class AgentChatResponse(BaseModel):
    answer: str
    track_ids: list[int]
    model: str | None


class AgentMemoryRequest(BaseModel):
    user_id: str = Field(min_length=1, max_length=64)
    content: str = Field(min_length=1, max_length=500)


class AgentMemoryUpdate(BaseModel):
    content: str = Field(min_length=1, max_length=500)
    expected_revision: int = Field(ge=1)


class AgentMemoryOut(BaseModel):
    id: int
    content: str
    revision: int

    @classmethod
    def of(cls, memory: AgentMemory) -> "AgentMemoryOut":
        return cls(id=memory.id, content=memory.content, revision=memory.revision)


# ---------- 路由 ----------


@app.get("/health")
def health() -> dict:
    return {"ok": True, "app": "GetVideo", "version": app.version}


@app.post("/agent/chat", response_model=AgentChatResponse)
def api_agent_chat(req: AgentChatRequest, session: Session = Depends(get_session)) -> AgentChatResponse:
    result, model = agent.answer(session, req.user_id, req.message.strip(),
                                 [track.model_dump() for track in req.local_tracks])
    return AgentChatResponse(answer=result.answer, track_ids=result.track_ids, model=model)


@app.get("/agent/memories/{user_id}", response_model=list[AgentMemoryOut])
def api_agent_memories(user_id: str, session: Session = Depends(get_session)) -> list[AgentMemoryOut]:
    rows = session.scalars(select(AgentMemory).where(
        AgentMemory.user_id == user_id, AgentMemory.deleted == 0).order_by(AgentMemory.id).limit(50))
    return [AgentMemoryOut.of(row) for row in rows]


@app.post("/agent/memories", response_model=AgentMemoryOut)
def api_agent_memory_create(req: AgentMemoryRequest, session: Session = Depends(get_session)) -> AgentMemoryOut:
    extraction_agent.safe_text(req.content)
    if not req.content.strip():
        raise HTTPException(400, "偏好不能为空")
    if len(api_agent_memories(req.user_id, session)) >= 50:
        raise HTTPException(400, "最多保存 50 条音乐偏好")
    row = agent.save_memory(session, req.user_id, req.content)
    result = AgentMemoryOut.of(row)
    session.commit()
    return result


@app.put("/agent/memories/{user_id}/{memory_id}", response_model=AgentMemoryOut)
def api_agent_memory_update(user_id: str, memory_id: int, req: AgentMemoryUpdate,
                            session: Session = Depends(get_session)) -> AgentMemoryOut:
    extraction_agent.safe_text(req.content)
    if not req.content.strip():
        raise HTTPException(400, "偏好不能为空")
    try:
        row = agent.update_memory(session, user_id, memory_id, req.expected_revision, req.content)
    except ValueError as exc:
        raise HTTPException(409, str(exc)) from exc
    if row is None:
        raise HTTPException(404, "偏好不存在")
    result = AgentMemoryOut.of(row)
    session.commit()
    return result


@app.delete("/agent/memories/{user_id}/{memory_id}")
def api_agent_memory_delete(user_id: str, memory_id: int, session: Session = Depends(get_session)) -> dict:
    if not agent.delete_memory(session, user_id, memory_id):
        raise HTTPException(404, "偏好不存在")
    session.commit()
    return {"ok": True}


@app.post("/extract", response_model=ExtractResponse)
def api_extract(req: ExtractRequest, session: Session = Depends(get_session)) -> ExtractResponse:
    try:
        outcome = run_extract(req.text, user_id=req.user_id, force=req.force)
    except LinkError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except NeedsLoginError as exc:
        # 401 而不是 400：手机端据此弹扫码登录
        raise HTTPException(status_code=401, detail=str(exc)) from exc
    except ExtractorError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc

    track = session.get(Track, outcome.track_id)
    if track is None:  # pragma: no cover - 理论上不会发生
        raise HTTPException(status_code=500, detail="提取成功但记录丢失")
    return ExtractResponse(track=TrackOut.of(track), reused=outcome.reused)


@app.get("/tracks", response_model=list[TrackOut])
def api_tracks(
    q: str | None = Query(None, description="按标题模糊搜索"),
    platform: str | None = Query(None),
    limit: int = Query(50, ge=1, le=200),
    offset: int = Query(0, ge=0),
    session: Session = Depends(get_session),
) -> list[TrackOut]:
    stmt = select(Track).where(Track.deleted == 0, Track.owner_scope == "legacy")
    if q:
        stmt = stmt.where(Track.title.contains(q))
    if platform:
        stmt = stmt.where(Track.platform == platform)
    stmt = stmt.order_by(Track.updated_seq.desc()).limit(limit).offset(offset)
    return [TrackOut.of(t) for t in session.execute(stmt).scalars()]


@app.get("/sync", response_model=SyncResponse)
def api_sync(
    cursor: int = Query(0, ge=0, description="上次同步到的 updated_seq"),
    limit: int = Query(200, ge=1, le=500),
    session: Session = Depends(get_session),
) -> SyncResponse:
    """增量同步。

    含已删除记录（tombstone），手机端据此删本地文件 —— 所以这里
    不能过滤 deleted。
    """
    stmt = (
        select(Track)
        .where(Track.updated_seq > cursor, Track.owner_scope == "legacy")
        .order_by(Track.updated_seq.asc())
        .limit(limit + 1)
    )
    rows = list(session.execute(stmt).scalars())
    has_more = len(rows) > limit
    rows = rows[:limit]
    next_cursor = rows[-1].updated_seq if rows else cursor
    return SyncResponse(items=[TrackOut.of(t) for t in rows], next_cursor=next_cursor, has_more=has_more)


@app.get("/video/{track_id}")
@app.get("/audio/{track_id}", include_in_schema=False)
def api_audio(track_id: int, session: Session = Depends(get_session)) -> FileResponse:
    track = session.get(Track, track_id)
    if track is None or track.deleted or track.owner_scope != "legacy":
        raise HTTPException(status_code=404, detail="视频不存在")
    path = settings.audio_dir / track.audio_path
    if not path.exists():
        raise HTTPException(status_code=410, detail="记录还在但文件已丢失")
    return FileResponse(
        path,
        media_type={"mp4": "video/mp4", "webm": "video/webm", "mkv": "video/x-matroska", "m4a": "audio/mp4", "mp3": "audio/mpeg", "flac": "audio/flac",
                    "ogg": "audio/ogg", "opus": "audio/ogg"}.get(track.audio_format, "application/octet-stream"),
        # 中文文件名交给 starlette 处理 RFC 5987 编码
        filename=f"{track.title[:60]}.{track.audio_format}",
    )


# ---------- 登录 ----------


@app.get("/login/platforms")
def api_login_platforms() -> dict:
    """哪些平台能扫码，哪些只能导入凭证。"""
    return {
        "platforms": login.supported_platforms(),
        "notes": {
            "bilibili": "支持扫码登录",
            "douyin": f"扫码接口被平台风控拦截，请用凭证导入。{login.how_to_export()}",
        },
    }


@app.post("/login/qr", response_model=LoginStartResponse)
def api_login_start(req: LoginStartRequest) -> LoginStartResponse:
    try:
        session = login.start(req.user_id, req.platform)
    except login.LoginNotSupported as exc:
        # 501 而不是 400：不是请求错了，是这条路暂时走不通
        raise HTTPException(status_code=501, detail=str(exc)) from exc
    except login.LoginError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    return LoginStartResponse(
        session_id=session.session_id,
        platform=session.platform,
        qr_url=session.qr_url,
        qr_image_url=f"/login/qr/{session.session_id}/image",
        expires_in=session.expires_in,
    )


@app.get("/login/qr/{session_id}", response_model=LoginPollResponse)
def api_login_poll(session_id: str) -> LoginPollResponse:
    try:
        session = login.poll(session_id)
    except login.LoginError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    return LoginPollResponse(
        session_id=session.session_id,
        platform=session.platform,
        state=session.state.value,
        message=session.message,
    )


@app.get("/login/qr/{session_id}/image")
def api_login_qr_image(session_id: str) -> Response:
    """把二维码内容渲染成 PNG。

    服务端画好省得每个客户端各装一个二维码库，Android 端直接当图片加载。
    """
    try:
        session = login.poll(session_id)
    except login.LoginError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc

    import io

    import qrcode

    buf = io.BytesIO()
    qrcode.make(session.qr_url).save(buf, format="PNG")
    return Response(
        content=buf.getvalue(),
        media_type="image/png",
        # 二维码三分钟就废，不要让中间层缓存
        headers={"Cache-Control": "no-store"},
    )


@app.post("/login/cookies")
def api_login_import(req: CookieImportRequest) -> dict:
    try:
        return login.import_cookies(req.user_id, req.platform, req.cookies)
    except login.LoginError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.get("/credentials/{user_id}", response_model=list[CredentialOut])
def api_credentials(user_id: str) -> list[CredentialOut]:
    """只回状态，绝不回凭证内容。"""
    return [CredentialOut(**c) for c in credentials.list_for(user_id)]


@app.delete("/credentials/{user_id}/{platform}")
def api_credential_revoke(user_id: str, platform: str) -> dict:
    if not credentials.revoke(user_id, platform):
        raise HTTPException(status_code=404, detail="没有这份凭证")
    return {"ok": True}


from .account_api import router as account_resource_router
app.include_router(account_resource_router)

from .platform_accounts import router as platform_account_router
app.include_router(platform_account_router)


from .recreation_api import router as recreation_router
app.include_router(recreation_router)

from .failure_reports import router as failure_report_router
app.include_router(failure_report_router)

from .creator_api import router as creator_router
app.include_router(creator_router)

from .library_api import router as library_router
app.include_router(library_router)
