"""账号隔离的视频库管理 / 维护 / 解析接口。

补齐原来只在 CLI 或内部函数里的能力，所有写操作都按当前
``owner_scope`` 过滤，避免一个账号操作到另一个账号的视频。
"""
from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Literal

from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException, Query
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import select
from sqlalchemy.orm import Session

from . import classification, fingerprint, audio_integrity
from .accounts import current_user, db
from .api import TrackOut
from .config import ROOT
from .db import next_updated_seq
from .models import AppUser, CrawlState, ExtractJob, Track
from .sharelink import LinkError, resolve

router = APIRouter(prefix="/v2", tags=["library"])


class ResolveBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    text: str = Field(min_length=1, max_length=4000)


class ResolveOut(BaseModel):
    platform: str
    video_id: str
    track_key: str
    url: str
    page: int


class TrackPatch(BaseModel):
    model_config = ConfigDict(extra="forbid")
    title: str | None = Field(None, min_length=1, max_length=200)
    uploader: str | None = Field(None, max_length=100)
    description: str | None = Field(None, max_length=20000)
    categories: list[classification.Category] | None = Field(None, min_length=1, max_length=6)
    has_vocal: bool | None = None


class TrackDeleteOut(BaseModel):
    ok: bool
    track_id: int
    deleted: bool
    updated_seq: int


class MaintenanceOut(BaseModel):
    updated: int
    track_ids: list[int] = Field(default_factory=list)
    message: str | None = None


class DuplicateOut(BaseModel):
    track_ids: list[int]
    titles: list[str]
    similarity: float


class ExtractionJobOut(BaseModel):
    id: int
    platform: str | None = None
    video_id: str | None = None
    resolved_url: str | None = None
    status: str
    error: str | None = None
    track_id: int | None = None
    created_at: str | None = None
    finished_at: str | None = None


class SchedulerGroupOut(BaseModel):
    name: str
    interval: str
    enabled: bool
    top_n: int
    keyword_count: int
    url_count: int
    next_run: float | None = None
    last_run: float | None = None
    summary: dict | None = None


class SchedulerOut(BaseModel):
    configured: bool
    groups: list[SchedulerGroupOut] = Field(default_factory=list)
    error: str | None = None


class SchedulerRunOut(BaseModel):
    scheduled: bool
    groups: list[str]
    message: str


def _iso(value: datetime | None) -> str | None:
    if value is None:
        return None
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    return value.isoformat()


def _owned_track(session: Session, track_id: int, user: AppUser) -> Track:
    track = session.get(Track, track_id)
    if track is None or track.owner_scope != user.id:
        raise HTTPException(404, "视频不存在")
    return track


def _out(track: Track):
    result = TrackOut.of(track)
    result.download_url = f"/v2/video/{track.id}"
    return result


@router.post("/resolve", response_model=ResolveOut)
def resolve_link(body: ResolveBody):
    """只解析分享文本/视频链接，不下载；等价于 CLI 的 resolve。"""
    try:
        link = resolve(body.text)
    except LinkError as exc:
        raise HTTPException(400, str(exc)) from exc
    return ResolveOut(
        platform=link.platform.value,
        video_id=link.video_id,
        track_key=link.track_key,
        url=link.url,
        page=link.page,
    )


@router.get("/tracks/{track_id}", response_model=TrackOut)
def get_track(track_id: int, user: AppUser = Depends(current_user), session: Session = Depends(db)):
    return _out(_owned_track(session, track_id, user))


@router.patch("/tracks/{track_id}", response_model=TrackOut)
def patch_track(
    track_id: int,
    body: TrackPatch,
    user: AppUser = Depends(current_user),
    session: Session = Depends(db),
):
    track = _owned_track(session, track_id, user)
    changed = False
    if body.title is not None:
        title = body.title.strip()
        if not title:
            raise HTTPException(400, "标题不能为空")
        track.title = title
        changed = True
    if body.uploader is not None:
        track.uploader = body.uploader.strip() or None
        changed = True
    if body.description is not None:
        track.description = body.description.strip() or None
        changed = True
    if body.categories is not None:
        track.categories_json = json.dumps(body.categories, ensure_ascii=False)
        track.classify_stage = "manual"
        changed = True
    if body.has_vocal is not None:
        track.has_vocal = int(body.has_vocal)
        track.classify_stage = "manual"
        changed = True
    if not changed:
        raise HTTPException(400, "没有要修改的字段")
    track.updated_seq = next_updated_seq(session)
    session.commit()
    return _out(track)


@router.delete("/tracks/{track_id}", response_model=TrackDeleteOut)
def delete_track(track_id: int, user: AppUser = Depends(current_user), session: Session = Depends(db)):
    """逻辑删除，保留服务器文件；同步时会下发 tombstone。"""
    track = _owned_track(session, track_id, user)
    if not track.deleted:
        track.deleted = 1
        track.updated_seq = next_updated_seq(session)
        session.commit()
    return TrackDeleteOut(ok=True, track_id=track.id, deleted=True, updated_seq=track.updated_seq)


@router.post("/tracks/{track_id}/restore", response_model=TrackOut)
def restore_track(track_id: int, user: AppUser = Depends(current_user), session: Session = Depends(db)):
    track = _owned_track(session, track_id, user)
    if track.deleted:
        track.deleted = 0
        track.updated_seq = next_updated_seq(session)
        session.commit()
    return _out(track)


@router.get("/extraction-jobs", response_model=list[ExtractionJobOut])
def extraction_jobs(
    limit: int = Query(50, ge=1, le=200),
    user: AppUser = Depends(current_user),
    session: Session = Depends(db),
):
    rows = session.scalars(
        select(ExtractJob)
        .where(ExtractJob.user_id == user.id)
        .order_by(ExtractJob.id.desc())
        .limit(limit)
    ).all()
    return [
        ExtractionJobOut(
            id=row.id,
            platform=row.platform,
            video_id=row.video_id,
            resolved_url=row.resolved_url,
            status=row.status.value,
            error=row.error,
            track_id=row.track_id,
            created_at=_iso(row.created_at),
            finished_at=_iso(row.finished_at),
        )
        for row in rows
    ]


@router.post("/maintenance/reclassify", response_model=MaintenanceOut)
def reclassify_tracks(
    limit: int = Query(100, ge=1, le=1000),
    force: bool = False,
    user: AppUser = Depends(current_user),
):
    count = classification.reclassify(limit=limit, force=force, owner_scope=user.id)
    return MaintenanceOut(updated=count, message=f"已更新 {count} 条分类")


@router.post("/maintenance/repair-audio-sizes", response_model=MaintenanceOut)
def repair_audio_sizes(user: AppUser = Depends(current_user)):
    ids = audio_integrity.repair_legacy_audio_sizes(owner_scope=user.id)
    return MaintenanceOut(updated=len(ids), track_ids=ids, message=f"已修复 {len(ids)} 条视频大小")


@router.post("/maintenance/fingerprints/backfill", response_model=MaintenanceOut)
def backfill_fingerprints(
    limit: int = Query(500, ge=1, le=5000),
    user: AppUser = Depends(current_user),
):
    count = fingerprint.backfill(limit=limit, owner_scope=user.id)
    return MaintenanceOut(updated=count, message=f"已补 {count} 条声音指纹")


@router.get("/maintenance/duplicates", response_model=list[DuplicateOut])
def duplicate_tracks(
    limit: int = Query(500, ge=1, le=5000),
    threshold: float = Query(0.95, ge=0.5, le=1.0),
    user: AppUser = Depends(current_user),
):
    return [DuplicateOut(**row) for row in fingerprint.duplicates(limit=limit, threshold=threshold, owner_scope=user.id)]


@router.get("/scheduler", response_model=SchedulerOut)
def scheduler_status(user: AppUser = Depends(current_user), session: Session = Depends(db)):
    """只读查看定时抓取配置和状态；不返回关键词/链接内容。"""
    path = ROOT / "config" / "keywords.yaml"
    if not path.exists():
        return SchedulerOut(configured=False)
    try:
        from .scheduler import load_schedule

        schedule = load_schedule(Path(path))
    except Exception as exc:
        return SchedulerOut(configured=False, error=str(exc))

    rows = {
        row.name: row
        for row in session.scalars(select(CrawlState).where(CrawlState.name.in_([g.name for g in schedule.groups]))).all()
    }
    groups: list[SchedulerGroupOut] = []
    for group in schedule.groups:
        # 全局组（user_id=None）或当前用户的组才可见；不泄露其他账号的任务。
        if group.user_id not in (None, user.id):
            continue
        state = rows.get(group.name)
        summary = None
        if state and state.summary_json:
            try:
                summary = json.loads(state.summary_json)
            except ValueError:
                summary = None
        groups.append(
            SchedulerGroupOut(
                name=group.name,
                interval=group.interval,
                enabled=group.enabled,
                top_n=group.top_n,
                keyword_count=len(group.keywords),
                url_count=len(group.urls),
                next_run=state.next_run if state else None,
                last_run=state.last_run if state else None,
                summary=summary,
            )
        )
    return SchedulerOut(configured=True, groups=groups)


@router.post("/scheduler/run-once", response_model=SchedulerRunOut, status_code=202)
def scheduler_run_once(
    background: BackgroundTasks,
    force: bool = False,
    user: AppUser = Depends(current_user),
):
    """只触发当前账号自己的定时抓取组；全局组仍由独立 scheduler worker 执行。"""
    path = ROOT / "config" / "keywords.yaml"
    if not path.exists():
        raise HTTPException(404, "定时抓取配置不存在")
    try:
        from .scheduler import Schedule, load_schedule, tick

        schedule = load_schedule(Path(path))
    except Exception as exc:
        raise HTTPException(400, f"定时抓取配置无效：{exc}") from exc

    groups = [group for group in schedule.groups if group.user_id == user.id and group.enabled]
    if not groups:
        raise HTTPException(400, "当前账号没有自己的定时抓取组；全局组请由 scheduler worker 执行")
    background.add_task(tick, Schedule(groups=groups), force=force)
    return SchedulerRunOut(
        scheduled=True,
        groups=[group.name for group in groups],
        message="已在后台触发；执行结果可使用 GET /v2/scheduler 查看",
    )
