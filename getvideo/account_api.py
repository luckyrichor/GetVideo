"""Account-only mobile API; legacy endpoints remain behind nginx Basic Auth."""
from io import BytesIO
from fastapi import APIRouter, Depends, HTTPException, Query
from fastapi.responses import FileResponse, Response
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import select
from sqlalchemy.orm import Session
import qrcode
from . import credentials, login
from .accounts import current_user, db
from .models import AppUser, Track
from .config import settings
from .pipeline import extract
from .extractors import ExtractorError, NeedsLoginError
from .sharelink import LinkError
from .api import TrackOut, ExtractResponse, SyncResponse, LoginStartResponse, LoginPollResponse

router = APIRouter(prefix='/v2', tags=['account resources'])


class ExtractBody(BaseModel):
    model_config = ConfigDict(extra='forbid')
    text: str = Field(min_length=1, max_length=4000)
    force: bool = False


class PlatformBody(BaseModel):
    model_config = ConfigDict(extra='forbid')
    platform: str = Field(pattern='^(bilibili|douyin)$')


class CookieBody(PlatformBody):
    cookies: str = Field(min_length=1, max_length=65536)


def track_out(track):
    result = TrackOut.of(track)
    result.download_url = f'/v2/video/{track.id}'
    return result


@router.post('/extract', response_model=ExtractResponse)
def extract_audio(body: ExtractBody, user: AppUser = Depends(current_user), session: Session = Depends(db)):
    try:
        result = extract(body.text, user_id=user.id, owner_scope=user.id, force=body.force)
    except LinkError as e:
        raise HTTPException(400, str(e)) from e
    except NeedsLoginError as e:
        # Distinct from expired GetVideo session.
        raise HTTPException(428, '平台需要重新授权：' + str(e)) from e
    except ExtractorError as e:
        raise HTTPException(502, str(e)) from e
    t = session.get(Track, result.track_id)
    if t is None or t.owner_scope != user.id:
        raise HTTPException(500, '提取结果归属异常')
    return ExtractResponse(track=track_out(t), reused=result.reused)


@router.get('/tracks', response_model=list[TrackOut])
def tracks(q: str | None = None, platform: str | None = None, limit: int = Query(50, ge=1, le=200),
           offset: int = Query(0, ge=0), user: AppUser = Depends(current_user), session: Session = Depends(db)):
    stmt = select(Track).where(Track.owner_scope == user.id, Track.deleted == 0)
    if q:
        stmt = stmt.where(Track.title.contains(q))
    if platform:
        stmt = stmt.where(Track.platform == platform)
    return [track_out(t) for t in session.scalars(stmt.order_by(Track.updated_seq.desc()).limit(limit).offset(offset))]


@router.get('/sync', response_model=SyncResponse)
def sync(cursor: int = Query(0, ge=0), limit: int = Query(200, ge=1, le=500),
         user: AppUser = Depends(current_user), session: Session = Depends(db)):
    rows = list(session.scalars(select(Track).where(Track.owner_scope == user.id, Track.updated_seq > cursor)
                               .order_by(Track.updated_seq).limit(limit + 1)))
    more = len(rows) > limit
    rows = rows[:limit]
    return SyncResponse(items=[track_out(t) for t in rows], next_cursor=rows[-1].updated_seq if rows else cursor, has_more=more)


@router.get('/video/{track_id}')
@router.get('/audio/{track_id}', include_in_schema=False)
def audio(track_id: int, user: AppUser = Depends(current_user), session: Session = Depends(db)):
    t = session.get(Track, track_id)
    if t is None or t.owner_scope != user.id or t.deleted:
        raise HTTPException(404, '视频不存在')
    path = settings.audio_dir / t.audio_path
    if not path.is_file():
        raise HTTPException(410, '视频文件已丢失')
    return FileResponse(path, media_type={'mp4': 'video/mp4', 'webm': 'video/webm', 'mkv': 'video/x-matroska'}.get(t.audio_format, 'application/octet-stream'), filename=f'{t.title[:60]}.{t.audio_format}')


@router.get('/platforms')
def platforms(user: AppUser = Depends(current_user)):
    return {'platforms': login.supported_platforms()}


@router.post('/platforms/qr', response_model=LoginStartResponse)
def start(body: PlatformBody, user: AppUser = Depends(current_user)):
    try:
        q = login.start(user.id, body.platform)
    except login.LoginNotSupported as e:
        raise HTTPException(501, str(e)) from e
    except login.LoginError as e:
        raise HTTPException(502, str(e)) from e
    return LoginStartResponse(session_id=q.session_id, platform=q.platform, qr_url=q.qr_url,
                              qr_image_url=f'/v2/platforms/qr/{q.session_id}/image', expires_in=q.expires_in)


@router.get('/platforms/qr/{sid}', response_model=LoginPollResponse)
def poll(sid: str, user: AppUser = Depends(current_user)):
    try:
        q = login.poll(sid, owner=user.id)
    except login.LoginError as e:
        raise HTTPException(404, '授权会话不存在或已过期') from e
    return LoginPollResponse(session_id=q.session_id, platform=q.platform, state=q.state.value, message=q.message)


@router.get('/platforms/qr/{sid}/image')
def image(sid: str, user: AppUser = Depends(current_user)):
    try:
        q = login.peek(sid, user.id)
    except login.LoginError as e:
        raise HTTPException(404, '授权会话不存在或已过期') from e
    data = BytesIO(); qrcode.make(q.qr_url).save(data, format='PNG')
    return Response(data.getvalue(), media_type='image/png', headers={'Cache-Control': 'no-store'})


@router.post('/platforms/cookies')
def import_cookies(body: CookieBody, user: AppUser = Depends(current_user)):
    try:
        login.import_cookies(user.id, body.platform, body.cookies)
    except login.LoginError as e:
        raise HTTPException(400, str(e)) from e
    return {'status': 'saved'}


@router.get('/platforms/credentials')
def credentials_list(user: AppUser = Depends(current_user)):
    return credentials.list_for(user.id)


@router.delete('/platforms/credentials/{platform}')
def revoke(platform: str, user: AppUser = Depends(current_user)):
    if platform not in login.supported_platforms():
        raise HTTPException(404, '平台不存在')
    login.cancel(user.id, platform)
    credentials.revoke(user.id, platform)
    return {'ok': True}


class MigrationBody(BaseModel):
    model_config = ConfigDict(extra='forbid')
    ticket: str = Field(min_length=32, max_length=128)


@router.post('/migration/claim')
def migrate(body: MigrationBody, user: AppUser = Depends(current_user)):
    from .credential_migration import claim
    return claim(body.ticket, user.id)


from .api import (AgentMemoryRequest, AgentMemoryUpdate, api_agent_memories,
                  api_agent_memory_create, api_agent_memory_update, api_agent_memory_delete)


@router.get('/memories/{user_id}')
def memories(user_id: str, user: AppUser = Depends(current_user), session: Session = Depends(db)):
    if user_id != user.id:
        raise HTTPException(404, '偏好不存在')
    return api_agent_memories(user.id, session)


@router.post('/memories')
def create_memory(body: AgentMemoryRequest, user: AppUser = Depends(current_user), session: Session = Depends(db)):
    if body.user_id != user.id:
        raise HTTPException(403, '不能修改其他账号偏好')
    return api_agent_memory_create(body, session)


@router.put('/memories/{user_id}/{memory_id}')
def update_memory(user_id: str, memory_id: int, body: AgentMemoryUpdate,
                  user: AppUser = Depends(current_user), session: Session = Depends(db)):
    if user_id != user.id:
        raise HTTPException(404, '偏好不存在')
    return api_agent_memory_update(user.id, memory_id, body, session)


@router.delete('/memories/{user_id}/{memory_id}')
def delete_memory(user_id: str, memory_id: int, user: AppUser = Depends(current_user), session: Session = Depends(db)):
    if user_id != user.id:
        raise HTTPException(404, '偏好不存在')
    return api_agent_memory_delete(user.id, memory_id, session)
