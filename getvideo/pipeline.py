"""提取链路的编排层。

    分享文本 → 解析 → 查重 → 下载+转码 → 打标 → 入库

这一层是唯一需要知道「全流程」的地方；上面的 API 和 CLI 都只调 extract()。
"""
from __future__ import annotations

import hashlib
import logging
import fcntl
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path

from sqlalchemy import select

from .config import settings
from .db import next_updated_seq, session_scope
from . import credentials, classification, fingerprint
from .extractors import ExtractorError, NeedsLoginError, get_extractor
from .guest import ensure_guest_cookies
from .models import ExtractJob, JobStatus, Track
from .sharelink import LinkError, Platform, resolve
from .tagging import write_tags
from .storage_scope import audio_scope

log = logging.getLogger(__name__)


@dataclass
class ExtractOutcome:
    track_id: int
    platform: str
    video_id: str
    title: str
    audio_path: str
    audio_bytes: int
    duration_sec: float | None
    source_url: str
    reused: bool  # True = 命中去重，没有重新下载


def _sha256(path: Path, chunk: int = 1 << 20) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        while block := f.read(chunk):
            h.update(block)
    return h.hexdigest()


def _cookie_file_for(user_id: str | None, platform: Platform) -> Path | None:
    """按优先级取 cookie：用户凭证 > 全局手工凭证 > 访客预热。

    访客 cookie 拿不到会员音质，但没有它 B 站直接回 412，所以它是
    兜底而不是可选项。
    """
    # 1. 该用户扫码登录 / 导入的凭证（解密后导出成 cookie 文件）
    if user_id:
        if path := credentials.ensure_cookie_file(user_id, platform.value):
            return path

    # 2. 手工放置的全局凭证（自用场景最省事）
    shared = settings.cookie_dir / f"{platform.value}.txt"
    if shared.exists() and not (user_id or "").startswith("acct_"):
        return shared

    # 3. 访客预热
    return ensure_guest_cookies(platform)


@contextmanager
def _link_lock(platform: str, video_id: str):
    """同一来源的并发提取串行化，防止文件覆盖与数据库去重互相竞态。"""
    key = hashlib.sha256(f"{platform}:{video_id}".encode()).hexdigest()
    with (settings.tmp_dir / f"extract-{key}.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        yield


def extract(raw_input: str, *, user_id: str | None = None, force: bool = False, owner_scope: str = "legacy") -> ExtractOutcome:
    """主入口：一段分享文本进，一条视频记录出。

    force=True 时忽略去重，重新下载并覆盖。
    """
    settings.ensure_dirs()

    with session_scope() as s:
        job = ExtractJob(raw_input=raw_input[:4000], user_id=user_id, status=JobStatus.RUNNING)
        s.add(job)
        s.flush()
        job_id = job.id

    def _fail(msg: str) -> None:
        with session_scope() as s:
            j = s.get(ExtractJob, job_id)
            if j:
                j.status = JobStatus.FAILED
                j.error = msg[:4000]
                from .models import utcnow

                j.finished_at = utcnow()

    try:
        # 1. 解析分享文本
        try:
            link = resolve(raw_input)
        except LinkError as exc:
            _fail(str(exc))
            raise

        with audio_scope(owner_scope), _link_lock(owner_scope + ":" + link.platform.value, link.track_key):
            with session_scope() as s:
                j = s.get(ExtractJob, job_id)
                j.platform, j.video_id, j.resolved_url = link.platform.value, link.track_key, link.url

            # 2. 查重：已经有了就直接返回，不重复下载
            if not force:
                with session_scope() as s:
                    existing = s.execute(
                        select(Track).where(
                            Track.owner_scope == owner_scope,
                            Track.platform == link.platform.value,
                            Track.video_id == link.track_key,
                            Track.deleted == 0,
                        )
                    ).scalar_one_or_none()
                    if existing and (settings.audio_dir / existing.audio_path).exists():
                        outcome = _to_outcome(existing, reused=True)
                        from .models import utcnow
                        job = s.get(ExtractJob, job_id)
                        job.status = JobStatus.DONE
                        job.track_id = existing.id
                        job.finished_at = utcnow()
                        log.info("命中去重，跳过下载: %s", link)
                        return outcome

            # 3. 下载 + 转码
            try:
                result = get_extractor(link.platform).extract(link, cookie_file=_cookie_file_for(user_id, link.platform))
            except NeedsLoginError as exc:
                # 带着凭证还被要求登录 —— 说明这份凭证过期了，标记掉，
                # 免得后续每次请求都拿它去撞同一堵墙
                if user_id and credentials.load(user_id, link.platform.value):
                    credentials.mark_needs_reauth(user_id, link.platform.value, str(exc))
                _fail(str(exc))
                raise
            except ExtractorError as exc:
                _fail(str(exc))
                raise

            classified = classification.classify(result.info)
            audio_fingerprint = fingerprint.compute(result.audio_path)

            # 4. 打标 + 算哈希
            write_tags(result.audio_path, result.info)
            audio_bytes = result.audio_path.stat().st_size
            digest = _sha256(result.audio_path)
            rel_path = str(result.audio_path.relative_to(settings.audio_dir))

            # 5. 入库（upsert：force 重下时覆盖原记录，不产生第二条）
            with session_scope() as s:
                track = s.execute(
                    select(Track).where(
                        Track.owner_scope == owner_scope, Track.platform == link.platform.value, Track.video_id == link.track_key
                    )
                ).scalar_one_or_none()
                if track is None:
                    track = Track(owner_scope=owner_scope, platform=link.platform.value, video_id=link.track_key, audio_path=rel_path, source_url=link.url)
                    s.add(track)

                info = result.info
                track.source_url = info.webpage_url or link.url
                track.title = info.title
                track.uploader = info.uploader
                track.description = (info.description or "")[:20000] or None
                track.thumbnail_url = info.thumbnail_url
                track.upload_date = info.upload_date
                track.duration_sec = info.duration_sec
                track.audio_path = rel_path
                track.audio_format = result.audio_format
                track.audio_bytes = audio_bytes
                track.audio_sha256 = digest
                classification.apply(track, classified)
                track.deleted = 0
                track.updated_seq = next_updated_seq(s)
                s.flush()
                fingerprint.store(s, track, audio_fingerprint)
                outcome = _to_outcome(track, reused=False)
                track_id = track.id

            _finish_job(job_id, track_id)
            log.info("提取成功: %s -> %s (%.1f KB)", link, rel_path, audio_bytes / 1024)
            return outcome
    except Exception as exc:
        _fail(str(exc))
        raise


def _finish_job(job_id: int, track_id: int) -> None:
    from .models import utcnow

    with session_scope() as s:
        j = s.get(ExtractJob, job_id)
        if j:
            j.status = JobStatus.DONE
            j.track_id = track_id
            j.finished_at = utcnow()


def _to_outcome(t: Track, *, reused: bool) -> ExtractOutcome:
    return ExtractOutcome(
        track_id=t.id,
        platform=t.platform,
        video_id=t.video_id,
        title=t.title,
        audio_path=t.audio_path,
        audio_bytes=t.audio_bytes or 0,
        duration_sec=t.duration_sec,
        source_url=t.source_url,
        reused=reused,
    )
