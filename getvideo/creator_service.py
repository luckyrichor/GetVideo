"""博主批量的编排层。

    主页链接 → 平台适配器取窗口（含互动数据）→ 建任务/任务项
             → 后台 worker 逐条走现有 pipeline.extract 入库

任务表只记进度和元数据，真正的视频仍落在原来的 ``tracks`` 表里，
所以账号隔离、增量同步、播放列表和去重逻辑全部复用。
"""
from __future__ import annotations

import json
import logging
import time
from dataclasses import asdict, dataclass
from datetime import datetime, timezone

from sqlalchemy import select, update

from .config import settings
from .creator_models import CreatorItem, CreatorJob
from .creators import (
    CreatorAuthError,
    CreatorError,
    CreatorRangeError,
    CreatorVideo,
    CreatorWindow,
    get_provider,
)
from .db import session_scope
from .extractors import ExtractorError, NeedsLoginError
from .pipeline import cookie_file_for, extract
from .sharelink import (
    CreatorRef,
    LinkError,
    Platform,
    find_urls,
    parse_creator_input,
    resolve_creator,
)

log = logging.getLogger(__name__)


@dataclass
class PreviewResult:
    ref: CreatorRef
    window: CreatorWindow
    fetched_at: str


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def validate_range(start: int, count: int) -> tuple[int, int]:
    if start < 1:
        raise CreatorRangeError("start 必须从 1 开始（最新一条为 1）")
    if start > settings.creator_max_start:
        raise CreatorRangeError(f"start 不能超过 {settings.creator_max_start}")
    if count < 1 or count > settings.creator_max_items:
        raise CreatorRangeError(f"count 必须在 1..{settings.creator_max_items} 之间")
    return start, count


def load_window(
    text: str,
    *,
    start: int,
    count: int,
    user_id: str | None,
    include_stats: bool = True,
    include_comments: bool = False,
    comment_limit: int = 5,
    platform: str | None = None,
) -> PreviewResult:
    start, count = validate_range(start, count)
    ref = resolve_ref(text, platform=platform, user_id=user_id)
    provider = get_provider(ref.platform)
    cookie_file = cookie_file_for(user_id, ref.platform)
    window = provider.fetch_window(ref, start=start, count=count, cookie_file=cookie_file)

    for video in window.items:
        try:
            provider.enrich(
                video,
                include_stats=include_stats,
                include_comments=include_comments,
                comment_limit=max(1, min(comment_limit, 20)),
                cookie_file=cookie_file,
            )
        except CreatorAuthError:
            raise
        except CreatorError as exc:
            log.warning("博主 %s 视频 %s 互动数据不可用: %s", ref.creator_id, video.video_id, exc)
        except Exception as exc:
            log.warning("博主 %s 视频 %s 互动数据抓取异常: %s", ref.creator_id, video.video_id, exc)

        if settings.creator_request_interval:
            time.sleep(settings.creator_request_interval)

    return PreviewResult(ref=ref, window=window, fetched_at=_now_iso())


def _platform_hint(value: str | None) -> Platform | None:
    if not value:
        return None
    try:
        return Platform(str(value).strip().lower())
    except ValueError as exc:
        raise LinkError(f"不支持的平台：{value}") from exc


def resolve_ref(text: str, *, platform: str | None = None, user_id: str | None = None) -> CreatorRef:
    """URL / 分享文案 / 抖音号 / B 站 UID / 昵称 → CreatorRef。

    先按 URL 解析（原有接口完全不改）；不是 URL 时再走平台适配器的
    UID/昵称解析。没有平台提示的裸数字会拒绝，避免把 B 站 UID 当成
    抖音号或反过来。
    """
    hint = _platform_hint(platform)

    try:
        ref = resolve_creator(text)
    except LinkError as url_error:
        # 输入里已经有链接但不是博主主页（例如单条视频），不要把它当昵称再猜。
        if find_urls(text):
            raise
        query = parse_creator_input(text, platform=hint)
        if query.platform is None:
            if query.kind == "numeric":
                raise LinkError(
                    "请注明平台：抖音号：<号码> 或 B站UID：<号码>；也可以直接传博主主页链接"
                ) from url_error
            platforms = [Platform.BILIBILI, Platform.DOUYIN]
        else:
            platforms = [query.platform]

        errors: list[str] = []
        for candidate_platform in platforms:
            try:
                provider = get_provider(candidate_platform)
                cookie_file = cookie_file_for(user_id, candidate_platform)
                return provider.resolve(query.value, cookie_file=cookie_file)
            except CreatorAuthError:
                # 明确指定了抖音且没有登录 Cookie 时，应该直接告诉用户去授权，
                # 不要用 B 站同名结果顶替。
                if query.platform is not None:
                    raise
                errors.append(f"{candidate_platform.value}: 需要平台登录")
            except CreatorNotFound as exc:
                errors.append(f"{candidate_platform.value}: {exc}")
            except CreatorUnavailable as exc:
                errors.append(f"{candidate_platform.value}: {exc}")
                if query.platform is not None:
                    raise

        raise CreatorNotFound("没有找到对应博主：" + "；".join(errors))

    if hint is not None and ref.platform is not hint:
        raise LinkError(f"链接是 {ref.platform.value}，但 platform 指定了 {hint.value}")
    return ref


def preview(
    text: str,
    *,
    start: int,
    count: int,
    user_id: str | None,
    include_stats: bool = True,
    include_comments: bool = False,
    comment_limit: int = 5,
    platform: str | None = None,
) -> PreviewResult:
    return load_window(
        text,
        start=start,
        count=count,
        user_id=user_id,
        include_stats=include_stats,
        include_comments=include_comments,
        comment_limit=comment_limit,
        platform=platform,
    )


def create_job(
    text: str,
    *,
    owner_scope: str,
    start: int,
    count: int,
    include_comments: bool = False,
    comment_limit: int = 5,
    force: bool = False,
    platform: str | None = None,
) -> int:
    result = load_window(
        text,
        start=start,
        count=count,
        user_id=owner_scope,
        include_stats=True,
        include_comments=include_comments,
        comment_limit=comment_limit,
        platform=platform,
    )
    window = result.window
    if not window.items:
        raise CreatorRangeError("选中的范围内没有视频")

    with session_scope() as session:
        job = CreatorJob(
            owner_scope=owner_scope,
            platform=result.ref.platform.value,
            creator_id=result.ref.creator_id,
            creator_name=window.profile.name,
            creator_url=result.ref.url,
            avatar_url=window.profile.avatar_url,
            start_index=start,
            item_count=len(window.items),
            total_videos=window.total,
            include_comments=1 if include_comments else 0,
            comment_limit=max(1, min(comment_limit, 20)),
            force=1 if force else 0,
            status="pending",
        )
        session.add(job)
        session.flush()
        for video in window.items:
            session.add(_item_of(job.id, video))
        job_id = job.id

    log.info("博主任务已创建: job=%s %s %s..%s", job_id, result.ref, start, start + len(window.items) - 1)
    return job_id


def _item_of(job_id: int, video: CreatorVideo) -> CreatorItem:
    comments = [asdict(comment) for comment in video.comments]
    return CreatorItem(
        job_id=job_id,
        position=video.position,
        platform=video.platform.value,
        video_id=video.video_id,
        title=video.title,
        description=video.description,
        source_url=video.source_url,
        cover_url=video.cover_url,
        duration_sec=video.duration_sec,
        published_at=video.published_at,
        platform_oid=video.platform_oid,
        prefetched_json=json.dumps(video.prefetched, ensure_ascii=False) if video.prefetched else None,
        view_count=video.stats.view_count,
        like_count=video.stats.like_count,
        comment_count=video.stats.comment_count,
        share_count=video.stats.share_count,
        favorite_count=video.stats.favorite_count,
        coin_count=video.stats.coin_count,
        danmaku_count=video.stats.danmaku_count,
        comments_json=json.dumps(comments, ensure_ascii=False) if comments else None,
        comments_status=video.comments_status,
        status="pending",
    )


def claim_next_job() -> int | None:
    """原子地取一条 pending 任务；SQLite 的 BEGIN IMMEDIATE 会把读取和写入串行化。"""
    with session_scope() as session:
        job = session.execute(
            select(CreatorJob).where(CreatorJob.status == "pending").order_by(CreatorJob.id).limit(1)
        ).scalar_one_or_none()
        if job is None:
            return None
        job.status = "running"
        job.started_at = datetime.now(timezone.utc)
        job.error = None
        return job.id


def process_job(job_id: int) -> None:
    """执行一条任务。可以重复调用：只处理 pending 的任务项。"""
    with session_scope() as session:
        job = session.get(CreatorJob, job_id)
        if job is None or job.status not in ("pending", "running"):
            return
        job.status = "running"
        job.started_at = job.started_at or datetime.now(timezone.utc)
        owner_scope = job.owner_scope
        force = bool(job.force)
        item_ids = [
            row.id
            for row in session.execute(
                select(CreatorItem)
                .where(CreatorItem.job_id == job_id, CreatorItem.status == "pending")
                .order_by(CreatorItem.position)
            ).scalars()
        ]
        started_at = job.started_at

    user_id = owner_scope if owner_scope.startswith("acct_") else None
    auth_error: str | None = None

    for item_id in item_ids:
        with session_scope() as session:
            job = session.get(CreatorJob, job_id)
            if job is None or job.status == "cancelled":
                return
            item = session.get(CreatorItem, item_id)
            if item is None or item.status != "pending":
                continue
            item.status = "running"
            item.error = None
            prefetched_json = item.prefetched_json

        prefetched = None
        if prefetched_json:
            try:
                prefetched = json.loads(prefetched_json)
            except (TypeError, ValueError):
                prefetched = None
        try:
            outcome = extract(
                item.source_url,
                user_id=user_id,
                force=force,
                owner_scope=owner_scope,
                prefetched_media=prefetched,
            )
        except NeedsLoginError as exc:
            _mark_item_failed(item_id, "需要平台重新授权：" + str(exc))
            _mark_job_auth_failed(job_id, str(exc))
            _refresh_counts(job_id)
            auth_error = str(exc)
            break
        except (LinkError, ExtractorError) as exc:
            _mark_item_failed(item_id, str(exc))
        except Exception as exc:  # 一条坏视频不应该让整批停住
            log.exception("博主任务 %s 视频 %s 下载异常", job_id, item_id)
            _mark_item_failed(item_id, f"下载异常：{exc}")
        else:
            _mark_item_done(item_id, outcome.track_id, outcome.reused)
        _refresh_counts(job_id)

        if settings.creator_download_interval:
            time.sleep(settings.creator_download_interval)

    with session_scope() as session:
        job = session.get(CreatorJob, job_id)
        if job is None or job.status == "cancelled":
            return
        rows = list(session.execute(select(CreatorItem).where(CreatorItem.job_id == job_id)).scalars())
        job.succeeded_count = sum(1 for row in rows if row.status == "done")
        job.failed_count = sum(1 for row in rows if row.status == "failed")
        job.processed_count = job.succeeded_count + job.failed_count
        if auth_error:
            job.status = "failed"
            job.error = "平台授权失效：" + auth_error
        elif job.failed_count and not job.succeeded_count:
            job.status = "failed"
            job.error = "全部视频下载失败"
        else:
            job.status = "done"
            job.error = None
        job.started_at = started_at
        job.finished_at = datetime.now(timezone.utc)


def _refresh_counts(job_id: int) -> None:
    with session_scope() as session:
        rows = list(session.execute(select(CreatorItem).where(CreatorItem.job_id == job_id)).scalars())
        job = session.get(CreatorJob, job_id)
        if job is None:
            return
        job.succeeded_count = sum(1 for row in rows if row.status == "done")
        job.failed_count = sum(1 for row in rows if row.status == "failed")
        job.processed_count = job.succeeded_count + job.failed_count


def _mark_item_done(item_id: int, track_id: int, reused: bool) -> None:
    with session_scope() as session:
        item = session.get(CreatorItem, item_id)
        if item is not None:
            item.status = "done"
            item.track_id = track_id
            item.reused = 1 if reused else 0
            item.error = None


def _mark_item_failed(item_id: int, error: str) -> None:
    with session_scope() as session:
        item = session.get(CreatorItem, item_id)
        if item is not None:
            item.status = "failed"
            item.error = error[:2000]


def _mark_job_auth_failed(job_id: int, error: str) -> None:
    with session_scope() as session:
        job = session.get(CreatorJob, job_id)
        if job is not None:
            job.error = ("平台授权失效：" + error)[:2000]


def fail_job(job_id: int, message: str) -> None:
    """worker 自身异常时兜底，避免任务永远停在 running。"""
    with session_scope() as session:
        job = session.get(CreatorJob, job_id)
        if job is None or job.status != "running":
            return
        job.status = "failed"
        job.error = str(message)[:2000]
        job.finished_at = datetime.now(timezone.utc)


def recover_interrupted_jobs() -> None:
    """进程重启后把「running」归还为 pending；视频下载本身可重入。"""
    with session_scope() as session:
        session.execute(
            update(CreatorJob)
            .where(CreatorJob.status == "running")
            .values(status="pending", started_at=None, error=None)
        )
        session.execute(
            update(CreatorItem).where(CreatorItem.status == "running").values(status="pending", error=None)
        )


def cancel_job(job_id: int, owner_scope: str) -> CreatorJob | None:
    with session_scope() as session:
        job = session.get(CreatorJob, job_id)
        if job is None or job.owner_scope != owner_scope:
            return None
        if job.status in ("pending", "running"):
            job.status = "cancelled"
            job.finished_at = datetime.now(timezone.utc)
            session.execute(
                update(CreatorItem)
                .where(CreatorItem.job_id == job_id, CreatorItem.status == "pending")
                .values(status="cancelled")
            )
            rows = list(session.execute(select(CreatorItem).where(CreatorItem.job_id == job_id)).scalars())
            job.succeeded_count = sum(1 for row in rows if row.status == "done")
            job.failed_count = sum(1 for row in rows if row.status == "failed")
            job.processed_count = job.succeeded_count + job.failed_count
        return job


def retry_job(job_id: int, owner_scope: str) -> CreatorJob | None:
    with session_scope() as session:
        job = session.get(CreatorJob, job_id)
        if job is None or job.owner_scope != owner_scope:
            return None
        if job.status in ("running", "pending"):
            raise CreatorRangeError("任务正在执行，不能重试")
        job.status = "pending"
        job.error = None
        job.processed_count = 0
        job.succeeded_count = 0
        job.failed_count = 0
        job.started_at = None
        job.finished_at = None
        session.execute(
            update(CreatorItem)
            .where(CreatorItem.job_id == job_id)
            .values(status="pending", error=None, track_id=None, reused=0)
        )
        return job


def get_job(job_id: int, owner_scope: str) -> CreatorJob | None:
    with session_scope() as session:
        job = session.get(CreatorJob, job_id)
        if job is None or job.owner_scope != owner_scope:
            return None
        return job


def list_jobs(owner_scope: str, *, limit: int = 20, offset: int = 0) -> list[CreatorJob]:
    with session_scope() as session:
        return list(
            session.execute(
                select(CreatorJob)
                .where(CreatorJob.owner_scope == owner_scope)
                .order_by(CreatorJob.id.desc())
                .limit(limit)
                .offset(offset)
            ).scalars()
        )


def list_items(job_id: int, owner_scope: str, *, status: str | None = None, limit: int = 50, offset: int = 0) -> list[CreatorItem]:
    with session_scope() as session:
        job = session.get(CreatorJob, job_id)
        if job is None or job.owner_scope != owner_scope:
            return []
        stmt = select(CreatorItem).where(CreatorItem.job_id == job_id)
        if status:
            stmt = stmt.where(CreatorItem.status == status)
        return list(session.execute(stmt.order_by(CreatorItem.position).limit(limit).offset(offset)).scalars())
