"""链路编排的单测。提取器打桩，不触网。"""
from __future__ import annotations

from pathlib import Path

import pytest

from getvideo import pipeline
from getvideo.config import settings
from getvideo.db import init_db, session_scope
from getvideo.extractors.base import ExtractResult, MediaInfo
from getvideo.models import AudioFingerprint, ExtractJob, JobStatus, Track
from getvideo.sharelink import Platform


class _FakeExtractor:
    """产出一个真实的小 MP4，让后续的打标和哈希都能真跑。"""

    platform = Platform.BILIBILI

    def __init__(self) -> None:
        self.calls = 0

    def extract(self, link, *, cookie_file=None) -> ExtractResult:
        self.calls += 1
        info = MediaInfo(
            platform=link.platform,
            video_id=link.track_key,
            title="测试视频",
            webpage_url=link.url,
            uploader="测试 up 主",
            duration_sec=1.0,
        )
        dest = settings.audio_dir / link.platform.value / f"{link.track_key}-测试视频.mp4"
        dest.parent.mkdir(parents=True, exist_ok=True)
        import subprocess

        subprocess.run(
            ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
             "-f", "lavfi", "-i", "testsrc2=size=128x96:rate=15:duration=1",
             "-f", "lavfi", "-i", "sine=frequency=440:duration=1",
             "-c:v", "libx264", "-c:a", "aac", "-shortest", str(dest)],
            check=True,
        )
        return ExtractResult(info=info, audio_path=dest, audio_format="mp4", audio_bytes=dest.stat().st_size)


@pytest.fixture
def fake(monkeypatch):
    init_db()
    ex = _FakeExtractor()
    monkeypatch.setattr(pipeline, "get_extractor", lambda _p: ex)
    # 不要在测试里触网做 cookie 预热
    monkeypatch.setattr(pipeline, "ensure_guest_cookies", lambda _p: None)
    with session_scope() as s:
        s.query(ExtractJob).delete()
        s.query(AudioFingerprint).filter(AudioFingerprint.track_id.in_(
            s.query(Track.id).filter(Track.video_id.like("BV0000000001%"))
        )).delete(synchronize_session=False)
        s.query(Track).filter(Track.video_id.like("BV0000000001%")).delete()
    yield ex


def test_extract_creates_track(fake):
    out = pipeline.extract("https://www.bilibili.com/video/BV0000000001")
    assert fake.calls == 1
    assert out.reused is False
    assert out.title == "测试视频"
    assert (settings.audio_dir / out.audio_path).exists()
    assert out.audio_bytes == (settings.audio_dir / out.audio_path).stat().st_size

    with session_scope() as s:
        t = s.get(Track, out.track_id)
        assert t.audio_sha256 and len(t.audio_sha256) == 64
        assert t.updated_seq > 0
        assert t.audio_bytes == out.audio_bytes


def test_second_call_hits_dedupe(fake):
    first = pipeline.extract("https://www.bilibili.com/video/BV0000000001")
    second = pipeline.extract("https://www.bilibili.com/video/BV0000000001")
    assert fake.calls == 1, "第二次不应该重新下载"
    assert second.reused is True
    assert second.track_id == first.track_id


def test_force_redownloads_without_duplicating(fake):
    first = pipeline.extract("https://www.bilibili.com/video/BV0000000001")
    again = pipeline.extract("https://www.bilibili.com/video/BV0000000001", force=True)
    assert fake.calls == 2
    assert again.track_id == first.track_id, "force 应该覆盖原记录，而不是新增一条"


def test_bilibili_parts_are_distinct_and_deduped_independently(fake):
    base = "https://www.bilibili.com/video/BV0000000001"
    first = pipeline.extract(base)
    second = pipeline.extract(base + "?p=2")
    again = pipeline.extract(base + "?p=2")
    assert fake.calls == 2
    assert first.track_id != second.track_id
    assert second.reused is False
    assert again.reused is True
    assert again.track_id == second.track_id
    with session_scope() as s:
        assert s.get(Track, second.track_id).video_id == "BV0000000001-p2"


def test_updated_seq_advances_on_force(fake):
    first = pipeline.extract("https://www.bilibili.com/video/BV0000000001")
    with session_scope() as s:
        seq1 = s.get(Track, first.track_id).updated_seq
    pipeline.extract("https://www.bilibili.com/video/BV0000000001", force=True)
    with session_scope() as s:
        seq2 = s.get(Track, first.track_id).updated_seq
    assert seq2 > seq1, "重新提取后同步游标必须前进，否则手机端拉不到更新"


def test_bad_link_records_failed_job(fake):
    from getvideo.sharelink import LinkError

    with pytest.raises(LinkError):
        pipeline.extract("这里没有链接")
    with session_scope() as s:
        job = s.query(ExtractJob).order_by(ExtractJob.id.desc()).first()
        assert job.status is JobStatus.FAILED
        assert job.error


def test_unexpected_failure_records_failed_job(fake, monkeypatch):
    def fail(_path):
        raise OSError("disk failure")
    monkeypatch.setattr(pipeline, "_sha256", fail)
    with pytest.raises(OSError):
        pipeline.extract("https://www.bilibili.com/video/BV0000000001")
    with session_scope() as s:
        job = s.query(ExtractJob).order_by(ExtractJob.id.desc()).first()
        assert job.status is JobStatus.FAILED
        assert job.finished_at is not None


def test_concurrent_same_video_downloads_once(fake):
    from concurrent.futures import ThreadPoolExecutor
    with ThreadPoolExecutor(max_workers=4) as pool:
        outcomes = list(pool.map(lambda _: pipeline.extract(
            "https://www.bilibili.com/video/BV0000000001"), range(4)))
    assert fake.calls == 1
    assert sum(not out.reused for out in outcomes) == 1
    assert len({out.track_id for out in outcomes}) == 1


def test_account_scope_dedupe_and_force_do_not_touch_other_account(fake, monkeypatch):
    from getvideo.extractors.ytdlp import _move_into_library
    original = fake.extract
    def scoped(link, cookie_file=None):
        result = original(link, cookie_file=cookie_file)
        result.audio_path = _move_into_library(result.audio_path, result.info)
        return result
    monkeypatch.setattr(fake, 'extract', scoped)
    a = pipeline.extract('https://www.bilibili.com/video/BV0000000001', user_id='acct_scope_test_a', owner_scope='acct_scope_test_a')
    before = (settings.audio_dir / a.audio_path).read_bytes()
    b = pipeline.extract('https://www.bilibili.com/video/BV0000000001', user_id='acct_scope_test_b', owner_scope='acct_scope_test_b')
    assert a.track_id != b.track_id and a.audio_path != b.audio_path
    assert fake.calls == 2
    assert pipeline.extract('https://www.bilibili.com/video/BV0000000001', user_id='acct_scope_test_b', owner_scope='acct_scope_test_b').reused
    pipeline.extract('https://www.bilibili.com/video/BV0000000001', user_id='acct_scope_test_b', owner_scope='acct_scope_test_b', force=True)
    assert fake.calls == 3
    assert (settings.audio_dir / a.audio_path).read_bytes() == before
