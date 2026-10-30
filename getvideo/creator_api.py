"""博主批量 HTTP 接口（账号隔离，位于 /v2）。

当前 APK 还没有接这些路由；接口先按手机端将来能直接消费的形状返回。
"""
from __future__ import annotations

import json
from datetime import datetime, timezone
from typing import Literal

from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import AliasChoices, BaseModel, ConfigDict, Field

from . import creator_service
from .accounts import current_user
from .config import settings
from .creator_models import CreatorItem, CreatorJob
from .creators import (
    CreatorAuthError,
    CreatorError,
    CreatorNotFound,
    CreatorRangeError,
    CreatorUnavailable,
)
from .creators.base import CreatorProfile, CreatorVideo, VideoStats
from .models import AppUser
from .sharelink import LinkError

router = APIRouter(prefix="/v2/creators", tags=["creator batch"])


class CreatorPreviewBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    url: str = Field(
        min_length=1,
        max_length=2000,
        validation_alias=AliasChoices("url", "input", "text"),
        description="博主主页链接、分享文案，或裸抖音号/B站UID/昵称；裸 ID 不需要加「抖音号：」前缀",
    )
    platform: Literal["bilibili", "douyin"] | None = Field(
        None, description="非 URL 输入时可指定平台，避免同名/纯数字歧义"
    )
    start: int = Field(1, ge=1, le=5000, description="从最新一条开始数，1-based")
    count: int = Field(10, ge=1, le=50, description="本次取多少条")
    include_stats: bool = Field(True, description="是否补齐点赞/评论/收藏等互动数据")
    include_comments: bool = Field(False, description="是否抓取热门评论正文（B站）")
    comment_limit: int = Field(5, ge=1, le=20, description="每条视频抓多少条评论")


class CreatorJobBody(CreatorPreviewBody):
    force: bool = Field(False, description="忽略去重，重新下载")


class CreatorStatsOut(BaseModel):
    view_count: int | None = None
    like_count: int | None = None
    comment_count: int | None = None
    share_count: int | None = None
    favorite_count: int | None = None
    coin_count: int | None = None
    danmaku_count: int | None = None

    @classmethod
    def of(cls, stats: VideoStats | CreatorItem) -> "CreatorStatsOut":
        return cls(
            view_count=getattr(stats, "view_count", None),
            like_count=getattr(stats, "like_count", None),
            comment_count=getattr(stats, "comment_count", None),
            share_count=getattr(stats, "share_count", None),
            favorite_count=getattr(stats, "favorite_count", None),
            coin_count=getattr(stats, "coin_count", None),
            danmaku_count=getattr(stats, "danmaku_count", None),
        )


class CreatorCommentOut(BaseModel):
    author: str
    content: str
    like_count: int | None = None
    created_at: str | None = None
    reply_count: int | None = None


class CreatorVideoOut(BaseModel):
    position: int
    video_id: str
    title: str
    description: str | None = None
    source_url: str
    cover_url: str | None = None
    duration_sec: float | None = None
    published_at: str | None = None
    stats: CreatorStatsOut = Field(default_factory=CreatorStatsOut)
    comments: list[CreatorCommentOut] = Field(default_factory=list)
    comments_status: str | None = None
    download_status: str | None = None
    track_id: int | None = None
    reused: bool | None = None
    error: str | None = None


class CreatorProfileOut(BaseModel):
    platform: str
    creator_id: str
    name: str
    avatar_url: str | None = None
    url: str
    total_videos: int | None = None
    follower_count: int | None = None
    signature: str | None = None

    @classmethod
    def of(cls, profile: CreatorProfile) -> "CreatorProfileOut":
        return cls(
            platform=profile.platform.value,
            creator_id=profile.creator_id,
            name=profile.name,
            avatar_url=profile.avatar_url,
            url=profile.url,
            total_videos=profile.total_videos,
            follower_count=profile.follower_count,
            signature=profile.signature,
        )


class CreatorRangeOut(BaseModel):
    start: int
    count: int


class CreatorPreviewOut(BaseModel):
    profile: CreatorProfileOut
    range: CreatorRangeOut
    items: list[CreatorVideoOut]
    fetched_at: str


class CreatorJobSummary(BaseModel):
    job_id: int
    status: str
    platform: str
    creator_id: str
    creator_name: str
    creator_url: str
    avatar_url: str | None = None
    start: int
    count: int
    total_videos: int | None = None
    processed_count: int
    succeeded_count: int
    failed_count: int
    force: bool
    include_comments: bool
    comment_limit: int
    error: str | None = None
    created_at: str
    started_at: str | None = None
    finished_at: str | None = None


class CreatorJobOut(CreatorJobSummary):
    items: list[CreatorVideoOut] = Field(default_factory=list)


def _iso(value: datetime | None) -> str | None:
    if value is None:
        return None
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    return value.isoformat()


def _video_out(video: CreatorVideo) -> CreatorVideoOut:
    return CreatorVideoOut(
        position=video.position,
        video_id=video.video_id,
        title=video.title,
        description=video.description,
        source_url=video.source_url,
        cover_url=video.cover_url,
        duration_sec=video.duration_sec,
        published_at=video.published_at,
        stats=CreatorStatsOut.of(video.stats),
        comments=[
            CreatorCommentOut(
                author=comment.author,
                content=comment.content,
                like_count=comment.like_count,
                created_at=comment.created_at,
                reply_count=comment.reply_count,
            )
            for comment in video.comments
        ],
        comments_status=video.comments_status,
    )


def _comments_of(item: CreatorItem) -> list[CreatorCommentOut]:
    if not item.comments_json:
        return []
    try:
        raw = json.loads(item.comments_json)
    except (TypeError, ValueError):
        return []
    result: list[CreatorCommentOut] = []
    for row in raw if isinstance(raw, list) else []:
        if not isinstance(row, dict):
            continue
        result.append(
            CreatorCommentOut(
                author=str(row.get("author") or ""),
                content=str(row.get("content") or ""),
                like_count=row.get("like_count"),
                created_at=row.get("created_at"),
                reply_count=row.get("reply_count"),
            )
        )
    return result


def _item_out(item: CreatorItem) -> CreatorVideoOut:
    return CreatorVideoOut(
        position=item.position,
        video_id=item.video_id,
        title=item.title,
        description=item.description,
        source_url=item.source_url,
        cover_url=item.cover_url,
        duration_sec=item.duration_sec,
        published_at=item.published_at,
        stats=CreatorStatsOut.of(item),
        comments=_comments_of(item),
        comments_status=item.comments_status,
        download_status=item.status,
        track_id=item.track_id,
        reused=bool(item.reused) if item.reused is not None else None,
        error=item.error,
    )


def _summary(job: CreatorJob) -> CreatorJobSummary:
    return CreatorJobSummary(
        job_id=job.id,
        status=job.status,
        platform=job.platform,
        creator_id=job.creator_id,
        creator_name=job.creator_name,
        creator_url=job.creator_url,
        avatar_url=job.avatar_url,
        start=job.start_index,
        count=job.item_count,
        total_videos=job.total_videos,
        processed_count=job.processed_count,
        succeeded_count=job.succeeded_count,
        failed_count=job.failed_count,
        force=bool(job.force),
        include_comments=bool(job.include_comments),
        comment_limit=job.comment_limit,
        error=job.error,
        created_at=_iso(job.created_at) or "",
        started_at=_iso(job.started_at),
        finished_at=_iso(job.finished_at),
    )


def _job_out(job: CreatorJob, items: list[CreatorItem]) -> CreatorJobOut:
    return CreatorJobOut(**_summary(job).model_dump(), items=[_item_out(item) for item in items])


def _raise(exc: Exception) -> None:
    if isinstance(exc, LinkError):
        raise HTTPException(400, str(exc)) from exc
    if isinstance(exc, CreatorRangeError):
        raise HTTPException(400, str(exc)) from exc
    if isinstance(exc, CreatorNotFound):
        raise HTTPException(404, str(exc)) from exc
    if isinstance(exc, CreatorAuthError):
        # 与 /v2/extract 的平台授权失效保持同一语义。
        raise HTTPException(428, "平台需要重新授权：" + str(exc)) from exc
    if isinstance(exc, CreatorUnavailable):
        raise HTTPException(503, str(exc)) from exc
    if isinstance(exc, CreatorError):
        raise HTTPException(502, str(exc)) from exc
    raise exc


@router.post("/preview", response_model=CreatorPreviewOut)
def preview(body: CreatorPreviewBody, user: AppUser = Depends(current_user)):
    try:
        result = creator_service.preview(
            body.url,
            start=body.start,
            count=body.count,
            user_id=user.id,
            include_stats=body.include_stats,
            include_comments=body.include_comments,
            comment_limit=body.comment_limit,
            platform=body.platform,
        )
    except Exception as exc:
        _raise(exc)

    return CreatorPreviewOut(
        profile=CreatorProfileOut.of(result.window.profile),
        range=CreatorRangeOut(start=body.start, count=body.count),
        items=[_video_out(video) for video in result.window.items],
        fetched_at=result.fetched_at,
    )


@router.post("/jobs", response_model=CreatorJobSummary, status_code=202)
def create_job(body: CreatorJobBody, user: AppUser = Depends(current_user)):
    try:
        job_id = creator_service.create_job(
            body.url,
            owner_scope=user.id,
            start=body.start,
            count=body.count,
            include_comments=body.include_comments,
            comment_limit=body.comment_limit,
            force=body.force,
            platform=body.platform,
        )
    except Exception as exc:
        _raise(exc)
    job = creator_service.get_job(job_id, user.id)
    if job is None:
        raise HTTPException(500, "任务创建后丢失")
    return _summary(job)


@router.get("/jobs", response_model=list[CreatorJobSummary])
def list_jobs(
    limit: int = Query(20, ge=1, le=100),
    offset: int = Query(0, ge=0),
    user: AppUser = Depends(current_user),
):
    return [_summary(job) for job in creator_service.list_jobs(user.id, limit=limit, offset=offset)]


@router.get("/jobs/{job_id}", response_model=CreatorJobOut)
def get_job(
    job_id: int,
    include_items: bool = Query(True),
    user: AppUser = Depends(current_user),
):
    job = creator_service.get_job(job_id, user.id)
    if job is None:
        raise HTTPException(404, "任务不存在")
    items = creator_service.list_items(job_id, user.id, limit=settings.creator_max_items) if include_items else []
    return _job_out(job, items)


@router.get("/jobs/{job_id}/items", response_model=list[CreatorVideoOut])
def get_job_items(
    job_id: int,
    status: str | None = Query(None, pattern="^(pending|running|done|failed|cancelled)$"),
    limit: int = Query(50, ge=1, le=50),
    offset: int = Query(0, ge=0),
    user: AppUser = Depends(current_user),
):
    job = creator_service.get_job(job_id, user.id)
    if job is None:
        raise HTTPException(404, "任务不存在")
    return [_item_out(item) for item in creator_service.list_items(job_id, user.id, status=status, limit=limit, offset=offset)]


@router.post("/jobs/{job_id}/cancel", response_model=CreatorJobSummary)
def cancel_job(job_id: int, user: AppUser = Depends(current_user)):
    job = creator_service.cancel_job(job_id, user.id)
    if job is None:
        raise HTTPException(404, "任务不存在")
    return _summary(job)


@router.post("/jobs/{job_id}/retry", response_model=CreatorJobSummary)
def retry_job(job_id: int, user: AppUser = Depends(current_user)):
    try:
        job = creator_service.retry_job(job_id, user.id)
    except CreatorRangeError as exc:
        raise HTTPException(409, str(exc)) from exc
    if job is None:
        raise HTTPException(404, "任务不存在")
    return _summary(job)
