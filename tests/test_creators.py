from __future__ import annotations

from types import SimpleNamespace
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from getvideo import creator_service
from getvideo.api import app
from getvideo.accounts import _windows
from getvideo.config import settings
from getvideo.creator_models import CreatorItem, CreatorJob
from getvideo.creators.base import (
    CreatorComment,
    CreatorNotFound,
    CreatorProfile,
    CreatorRangeError,
    CreatorUnavailable,
    CreatorVideo,
    CreatorWindow,
    VideoStats,
)
from getvideo.creators.bilibili import BilibiliCreatorProvider, _duration
from getvideo.creators.douyin import DouyinCreatorProvider
from getvideo.db import init_db, next_updated_seq, session_scope
from getvideo.extractors import ExtractorError
from getvideo.models import Track
from getvideo.sharelink import CreatorRef, LinkError, Platform, resolve_creator


class FakeProvider:
    platform = Platform.BILIBILI

    def __init__(self, total: int = 65, fail_video: str | None = None):
        self.total = total
        self.fail_video = fail_video
        self.enriched: list[str] = []

    def resolve(self, value, *, cookie_file):
        return CreatorRef(self.platform, value, f"https://space.bilibili.com/{value}/video")

    def fetch_window(self, ref, *, start, count, cookie_file):
        if start > self.total:
            raise CreatorRangeError(f"范围超出：该 UP 主只有 {self.total} 条公开视频")
        end = min(self.total, start + count - 1)
        items = [
            CreatorVideo(
                platform=Platform.BILIBILI,
                video_id=f"BVfake{i:08d}",
                title=f"视频 {i}",
                source_url=f"https://www.bilibili.com/video/BVfake{i:08d}",
                position=i,
                creator_id=ref.creator_id,
                published_at=f"2026-01-{i:02d}T00:00:00+00:00",
                stats=VideoStats(view_count=i * 10, comment_count=i),
            )
            for i in range(start, end + 1)
        ]
        return CreatorWindow(
            profile=CreatorProfile(
                platform=Platform.BILIBILI,
                creator_id=ref.creator_id,
                name="测试 UP 主",
                url=ref.url,
                total_videos=self.total,
                follower_count=123,
            ),
            items=items,
            total=self.total,
            has_more=end < self.total,
        )

    def enrich(self, video, *, include_stats, include_comments, comment_limit, cookie_file):
        self.enriched.append(video.video_id)
        if include_stats:
            video.stats.like_count = video.position * 100
        if include_comments:
            video.comments = [
                CreatorComment(author="观众", content=f"评论 {video.position}", like_count=7)
            ]
            video.comments_status = "ok"
        return video


def _register(client: TestClient) -> tuple[str, dict]:
    _windows.clear()
    phone = "139" + str(int(uuid4().hex[:10], 16))[-8:].zfill(8)
    response = client.post(
        "/account/register", json={"phone": phone, "password": "test-password-123"}
    )
    assert response.status_code == 200, response.text
    data = response.json()
    return data["user_id"], {"X-GetVideo-Session": data["session_token"]}


def _use_fake(monkeypatch, provider):
    monkeypatch.setattr(creator_service, "get_provider", lambda _platform: provider)
    monkeypatch.setattr(creator_service, "cookie_file_for", lambda _user, _platform: None)
    monkeypatch.setattr(settings, "creator_request_interval", 0)
    monkeypatch.setattr(settings, "creator_download_interval", 0)


def test_resolve_creator_accepts_homepages_and_rejects_single_video():
    assert resolve_creator("https://space.bilibili.com/2/video").creator_id == "2"
    assert resolve_creator("https://space.bilibili.com/2?spm_id_from=333").platform is Platform.BILIBILI
    douyin = resolve_creator(
        "打开抖音看看 https://www.douyin.com/user/MS4wLjABAAAAEKnfa654JAJ_N5lgZDQluwsxmY0lhfmEYNQBBkwGG98"
    )
    assert douyin.platform is Platform.DOUYIN
    assert douyin.creator_id.startswith("MS4wLjABAAAA")

    with pytest.raises(LinkError, match="单个视频链接"):
        resolve_creator("https://www.bilibili.com/video/BV1ujNV6qEXg")
    with pytest.raises(LinkError, match="单个视频链接"):
        resolve_creator("https://www.douyin.com/video/6961737553342991651")


def test_preview_slices_requested_range_newest_first(monkeypatch):
    _use_fake(monkeypatch, FakeProvider(total=65))
    result = creator_service.preview(
        "https://space.bilibili.com/2/video",
        start=11,
        count=5,
        user_id=None,
        include_stats=True,
        include_comments=True,
        comment_limit=2,
    )
    assert [v.position for v in result.window.items] == [11, 12, 13, 14, 15]
    assert result.window.total == 65
    assert result.window.profile.name == "测试 UP 主"
    assert all(v.stats.like_count for v in result.window.items)
    assert all(v.comments for v in result.window.items)


def test_create_and_process_job(monkeypatch):
    init_db()
    _use_fake(monkeypatch, FakeProvider(total=3))
    owner = "acct_" + uuid4().hex

    job_id = creator_service.create_job(
        "https://space.bilibili.com/2/video", owner_scope=owner, start=1, count=3
    )

    def fake_extract(url, *, user_id=None, force=False, owner_scope="legacy", prefetched_media=None):
        assert user_id == owner
        assert owner_scope == owner
        assert prefetched_media is None
        video_id = url.rsplit("/", 1)[-1]
        with session_scope() as session:
            track = Track(
                owner_scope=owner,
                platform="bilibili",
                video_id=video_id,
                title=video_id,
                source_url=url,
                audio_path=f"{video_id}.mp4",
                updated_seq=next_updated_seq(session),
            )
            session.add(track)
            session.flush()
            return SimpleNamespace(track_id=track.id, reused=False)

    monkeypatch.setattr(creator_service, "extract", fake_extract)
    creator_service.process_job(job_id)

    with session_scope() as session:
        job = session.get(CreatorJob, job_id)
        items = list(
            session.query(CreatorItem).filter(CreatorItem.job_id == job_id).order_by(CreatorItem.position)
        )
        assert job.status == "done"
        assert (job.processed_count, job.succeeded_count, job.failed_count) == (3, 3, 0)
        assert [item.status for item in items] == ["done", "done", "done"]
        assert all(item.track_id for item in items)


def test_one_failed_video_does_not_stop_batch(monkeypatch):
    init_db()
    _use_fake(monkeypatch, FakeProvider(total=3))
    owner = "acct_" + uuid4().hex
    job_id = creator_service.create_job(
        "https://space.bilibili.com/2/video", owner_scope=owner, start=1, count=3
    )

    def fake_extract(url, **_kwargs):
        if url.endswith("BVfake00000002"):
            raise ExtractorError("视频已下架")
        with session_scope() as session:
            video_id = url.rsplit("/", 1)[-1]
            track = Track(
                owner_scope=owner,
                platform="bilibili",
                video_id=video_id,
                title=video_id,
                source_url=url,
                audio_path=f"{video_id}.mp4",
                updated_seq=next_updated_seq(session),
            )
            session.add(track)
            session.flush()
            return SimpleNamespace(track_id=track.id, reused=True)

    monkeypatch.setattr(creator_service, "extract", fake_extract)
    creator_service.process_job(job_id)

    with session_scope() as session:
        job = session.get(CreatorJob, job_id)
        items = list(
            session.query(CreatorItem).filter(CreatorItem.job_id == job_id).order_by(CreatorItem.position)
        )
        assert job.status == "done"
        assert (job.processed_count, job.succeeded_count, job.failed_count) == (3, 2, 1)
        assert [item.status for item in items] == ["done", "failed", "done"]
        assert "已下架" in items[1].error


def test_creator_api_is_account_scoped_and_supports_cancel(monkeypatch):
    init_db()
    _use_fake(monkeypatch, FakeProvider(total=20))
    with TestClient(app) as client:
        assert client.get("/v2/creators/jobs").status_code == 401
        alice, alice_headers = _register(client)
        bob, bob_headers = _register(client)

        preview = client.post(
            "/v2/creators/preview",
            headers=alice_headers,
            json={"url": "https://space.bilibili.com/2/video", "start": 1, "count": 2},
        )
        assert preview.status_code == 200, preview.text
        assert [item["position"] for item in preview.json()["items"]] == [1, 2]

        by_uid = client.post(
            "/v2/creators/preview",
            headers=alice_headers,
            json={"url": "B站UID：2", "platform": "bilibili", "start": 1, "count": 1},
        )
        assert by_uid.status_code == 200, by_uid.text
        assert by_uid.json()["profile"]["creator_id"] == "2"

        by_input = client.post(
            "/v2/creators/preview",
            headers=alice_headers,
            json={"input": "B站UID：2", "start": 1, "count": 1},
        )
        assert by_input.status_code == 200, by_input.text
        assert by_input.json()["profile"]["creator_id"] == "2"

        created = client.post(
            "/v2/creators/jobs",
            headers=alice_headers,
            json={"url": "https://space.bilibili.com/2/video", "start": 1, "count": 2},
        )
        assert created.status_code == 202, created.text
        job_id = created.json()["job_id"]
        assert created.json()["status"] == "pending"

        assert client.get(f"/v2/creators/jobs/{job_id}", headers=bob_headers).status_code == 404
        detail = client.get(f"/v2/creators/jobs/{job_id}", headers=alice_headers)
        assert detail.status_code == 200
        assert len(detail.json()["items"]) == 2
        assert [item["download_status"] for item in detail.json()["items"]] == ["pending", "pending"]
        pending_items = client.get(
            f"/v2/creators/jobs/{job_id}/items?status=pending", headers=alice_headers
        )
        assert pending_items.status_code == 200 and len(pending_items.json()) == 2

        listed = client.get("/v2/creators/jobs", headers=alice_headers).json()
        assert [row["job_id"] for row in listed] == [job_id]

        cancelled = client.post(f"/v2/creators/jobs/{job_id}/cancel", headers=alice_headers)
        assert cancelled.status_code == 200 and cancelled.json()["status"] == "cancelled"

        retried = client.post(f"/v2/creators/jobs/{job_id}/retry", headers=alice_headers)
        assert retried.status_code == 200 and retried.json()["status"] == "pending"


def test_creator_api_rejects_out_of_range_and_video_url(monkeypatch):
    _use_fake(monkeypatch, FakeProvider(total=3))
    with TestClient(app) as client:
        _, headers = _register(client)
        bad_range = client.post(
            "/v2/creators/preview",
            headers=headers,
            json={"url": "https://space.bilibili.com/2/video", "start": 0, "count": 2},
        )
        assert bad_range.status_code == 422
        video_url = client.post(
            "/v2/creators/preview",
            headers=headers,
            json={"url": "https://www.bilibili.com/video/BV1ujNV6qEXg", "start": 1, "count": 2},
        )
        assert video_url.status_code == 400
        assert "主页" in video_url.json()["detail"]


def test_bilibili_vlist_mapping_and_duration():
    assert _duration("01:02") == 62
    assert _duration("1:02:03") == 3723
    provider = BilibiliCreatorProvider()
    ref = resolve_creator("https://space.bilibili.com/2/video")
    video = provider._to_video(
        {
            "bvid": "BV1ujNV6qEXg",
            "aid": 116908221270151,
            "title": "帮不帮？",
            "description": "简介",
            "pic": "http://i2.hdslb.com/x.jpg",
            "created": 1783878096,
            "length": "00:22",
            "play": 758561,
            "comment": 9313,
            "video_review": 2765,
        },
        1,
        ref,
    )
    assert video.video_id == "BV1ujNV6qEXg"
    assert video.platform_oid == 116908221270151
    assert video.cover_url.startswith("https://")
    assert video.duration_sec == 22
    assert video.stats.view_count == 758561
    assert video.stats.comment_count == 9313
    assert video.stats.danmaku_count == 2765
    assert video.published_at.startswith("2026-")


def _aweme(aweme_id: str = "7000000000000000001", digg: int = 12) -> dict:
    return {
        "aweme_id": aweme_id,
        "desc": "抖音文案 #测试",
        "create_time": 1783878096,
        "author": {
            "sec_uid": "MS4wLjABAAAAEKnfa654JAJ_N5lgZDQluwsxmY0lhfmEYNQBBkwGG98",
            "nickname": "测试博主",
            "avatar_thumb": {"url_list": ["https://p3.douyinpic.com/avatar.jpg"]},
            "follower_count": 1234,
            "aweme_count": 9,
        },
        "video": {
            "duration": 15000,
            "cover": {"url_list": ["https://p3.douyinpic.com/cover.jpg"]},
            "play_addr": {"url_list": ["https://v3.douyinpic.com/play.mp4"]},
        },
        "statistics": {
            "digg_count": digg,
            "comment_count": 3,
            "share_count": 4,
            "collect_count": 5,
            "play_count": 6,
        },
    }


def test_douyin_aweme_mapping_and_window_from_bridge(monkeypatch):
    ref = resolve_creator(
        "https://www.douyin.com/user/MS4wLjABAAAAEKnfa654JAJ_N5lgZDQluwsxmY0lhfmEYNQBBkwGG98"
    )
    provider = DouyinCreatorProvider(client_factory=lambda **_kwargs: _FakeClient(403, "Blocked by ArgusSecurityPlugin"))

    class Bridge:
        def capture(self, _ref, *, need, cookie_file):
            return {
                "profile": _aweme()["author"],
                "items": [_aweme(f"700000000000000000{i}", digg=i) for i in range(1, 4)],
                "has_more": False,
            }

    monkeypatch.setattr(settings, "douyin_browser", True)
    provider._bridge_factory = Bridge
    window = provider.fetch_window(ref, start=2, count=2, cookie_file=None)
    assert [video.position for video in window.items] == [2, 3]
    assert [video.video_id for video in window.items] == [
        "7000000000000000002",
        "7000000000000000003",
    ]
    assert window.profile.name == "测试博主"
    assert window.profile.total_videos == 9  # 作者页作品数（含图集）
    assert window.total == 3  # 实际可下载的视频数
    assert window.profile.follower_count == 1234
    video = window.items[0]
    assert video.title == "抖音文案 #测试"
    assert video.duration_sec == 15
    assert video.stats.like_count == 2
    assert video.stats.comment_count == 3
    assert video.stats.favorite_count == 5
    assert video.prefetched["aweme_id"] == "7000000000000000002"
    assert video.prefetched["video"]["duration"] == 15000


def test_douyin_direct_argus_error_is_actionable(monkeypatch):
    monkeypatch.setattr(settings, "douyin_browser", False)
    ref = resolve_creator(
        "https://www.douyin.com/user/MS4wLjABAAAAEKnfa654JAJ_N5lgZDQluwsxmY0lhfmEYNQBBkwGG98"
    )
    provider = DouyinCreatorProvider(
        client_factory=lambda **_kwargs: _FakeClient(403, "Blocked by ArgusSecurityPlugin Uifid Not Found")
    )
    with pytest.raises(CreatorUnavailable, match="Argus"):
        provider.fetch_window(ref, start=1, count=2, cookie_file=None)


class _FakeResponse:
    def __init__(self, status_code: int, text: str):
        self.status_code = status_code
        self.text = text

    def json(self):
        import json

        return json.loads(self.text)


class _FakeClient:
    def __init__(self, status_code: int, text: str):
        self.status_code = status_code
        self.text = text

    def __enter__(self):
        return self

    def __exit__(self, *_exc):
        return False

    def get(self, *_args, **_kwargs):
        return _FakeResponse(self.status_code, self.text)


def test_douyin_direct_page_mapping(monkeypatch):
    import json

    monkeypatch.setattr(settings, "douyin_browser", False)
    ref = resolve_creator(
        "https://www.douyin.com/user/MS4wLjABAAAAEKnfa654JAJ_N5lgZDQluwsxmY0lhfmEYNQBBkwGG98"
    )
    payload = {
        "status_code": 0,
        "has_more": 0,
        "max_cursor": 0,
        "aweme_list": [_aweme("7000000000000000011", 11), _aweme("7000000000000000012", 12)],
    }
    provider = DouyinCreatorProvider(
        client_factory=lambda **_kwargs: _FakeClient(200, json.dumps(payload))
    )
    window = provider.fetch_window(ref, start=1, count=2, cookie_file=None)
    assert [video.video_id for video in window.items] == [
        "7000000000000000011",
        "7000000000000000012",
    ]
    assert window.profile.name == "测试博主"
    assert window.items[0].stats.like_count == 11


def test_auth_failure_stops_batch_and_leaves_remaining_pending(monkeypatch):
    init_db()
    _use_fake(monkeypatch, FakeProvider(total=3))
    owner = "acct_" + uuid4().hex
    job_id = creator_service.create_job(
        "https://space.bilibili.com/2/video", owner_scope=owner, start=1, count=3
    )

    def fake_extract(*_args, **_kwargs):
        from getvideo.extractors import NeedsLoginError

        raise NeedsLoginError("cookie 已过期")

    monkeypatch.setattr(creator_service, "extract", fake_extract)
    creator_service.process_job(job_id)

    with session_scope() as session:
        job = session.get(CreatorJob, job_id)
        items = list(
            session.query(CreatorItem).filter(CreatorItem.job_id == job_id).order_by(CreatorItem.position)
        )
        assert job.status == "failed"
        assert "授权" in job.error
        assert items[0].status == "failed"
        assert [item.status for item in items[1:]] == ["pending", "pending"]


def test_douyin_job_forwards_prefetched_aweme_to_download(monkeypatch):
    init_db()
    owner = "acct_" + uuid4().hex

    class DouyinProvider:
        platform = Platform.DOUYIN

        def fetch_window(self, ref, *, start, count, cookie_file):
            video = CreatorVideo(
                platform=Platform.DOUYIN,
                video_id="7000000000000000099",
                title="抖音测试",
                source_url="https://www.douyin.com/video/7000000000000000099",
                position=1,
                creator_id=ref.creator_id,
                stats=VideoStats(like_count=9, comment_count=2),
                prefetched={"aweme_id": "7000000000000000099", "video": {"play_addr": {"url_list": []}}},
            )
            return CreatorWindow(
                profile=CreatorProfile(
                    platform=Platform.DOUYIN,
                    creator_id=ref.creator_id,
                    name="抖音测试博主",
                    url=ref.url,
                    total_videos=1,
                ),
                items=[video],
                total=1,
                has_more=False,
            )

        def enrich(self, video, **_kwargs):
            return video

    monkeypatch.setattr(creator_service, "get_provider", lambda _platform: DouyinProvider())
    monkeypatch.setattr(creator_service, "cookie_file_for", lambda _user, _platform: None)
    monkeypatch.setattr(settings, "creator_request_interval", 0)
    monkeypatch.setattr(settings, "creator_download_interval", 0)

    job_id = creator_service.create_job(
        "https://www.douyin.com/user/MS4wLjABAAAAEKnfa654JAJ_N5lgZDQluwsxmY0lhfmEYNQBBkwGG98",
        owner_scope=owner,
        start=1,
        count=1,
    )
    seen = {}

    def fake_extract(url, *, user_id=None, force=False, owner_scope="legacy", prefetched_media=None):
        seen["prefetched"] = prefetched_media
        with session_scope() as session:
            track = Track(
                owner_scope=owner,
                platform="douyin",
                video_id="7000000000000000099",
                title="抖音测试",
                source_url=url,
                audio_path="douyin-test.mp4",
                updated_seq=next_updated_seq(session),
            )
            session.add(track)
            session.flush()
            return SimpleNamespace(track_id=track.id, reused=False)

    monkeypatch.setattr(creator_service, "extract", fake_extract)
    creator_service.process_job(job_id)

    assert seen["prefetched"]["aweme_id"] == "7000000000000000099"
    with session_scope() as session:
        item = session.query(CreatorItem).filter(CreatorItem.job_id == job_id).one()
        assert item.status == "done"
        assert item.prefetched_json


def test_douyin_extractor_uses_prefetched_play_url_without_detail(tmp_path, monkeypatch):
    """用本地 CDN 替身验证：有预取 aweme 时不再请求 detail 接口。"""
    import functools
    import http.server
    import subprocess
    import threading

    from getvideo.extractors.douyin import DouyinExtractor
    from getvideo.sharelink import ResolvedLink

    clip = tmp_path / "clip.mp4"
    subprocess.run(
        [
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "testsrc2=size=128x96:rate=15:duration=1",
            "-f", "lavfi", "-i", "sine=frequency=440:duration=1",
            "-c:v", "libx264", "-c:a", "aac", "-shortest", str(clip),
        ],
        check=True,
    )

    class Quiet(http.server.SimpleHTTPRequestHandler):
        def log_message(self, *_args):
            pass

    handler = functools.partial(Quiet, directory=str(tmp_path))
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        url = f"http://127.0.0.1:{server.server_address[1]}/clip.mp4"
        link = ResolvedLink(
            Platform.DOUYIN,
            "7000000000000000123",
            "https://www.douyin.com/video/7000000000000000123",
            "https://www.douyin.com/video/7000000000000000123",
        )
        aweme = {
            "aweme_id": "7000000000000000123",
            "desc": "本地预取作品",
            "create_time": 1783878096,
            "author": {"nickname": "本地博主", "sec_uid": "sec"},
            "video": {
                "duration": 1000,
                "play_addr": {"url_list": [url]},
                "cover": {"url_list": ["https://example.invalid/cover.jpg"]},
            },
        }

        def no_detail(*_args, **_kwargs):
            raise AssertionError("不应请求 aweme/detail")

        monkeypatch.setattr(DouyinExtractor, "_fetch_detail", no_detail)
        result = DouyinExtractor().extract(link, aweme=aweme)
        assert result.info.title == "本地预取作品"
        assert result.info.uploader == "本地博主"
        assert result.audio_format == "mp4"
        assert result.audio_path.exists() and result.audio_path.stat().st_size > 1024
        probe = subprocess.run(
            ["ffprobe", "-v", "error", "-show_entries", "stream=codec_type", "-of", "csv=p=0", str(result.audio_path)],
            capture_output=True, text=True, check=True,
        ).stdout
        assert "video" in probe and "audio" in probe
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


def test_parse_creator_input_supports_mobile_style_ids():
    from getvideo.sharelink import parse_creator_input

    assert parse_creator_input("抖音号：67194609689").platform is Platform.DOUYIN
    assert parse_creator_input("抖音号：67194609689").value == "67194609689"
    assert parse_creator_input("B站UID：2").platform is Platform.BILIBILI
    assert parse_creator_input("B站UID：2").value == "2"
    assert parse_creator_input("碧诗").kind == "nickname"
    assert parse_creator_input("67194609689", platform="douyin").platform is Platform.DOUYIN
    with pytest.raises(LinkError):
        parse_creator_input("抖音号：2", platform="bilibili")


def test_resolve_ref_accepts_bilibili_uid_without_network(monkeypatch):
    monkeypatch.setattr(creator_service, "cookie_file_for", lambda _user, _platform: None)
    ref = creator_service.resolve_ref("B站UID：2")
    assert ref.platform is Platform.BILIBILI
    assert ref.creator_id == "2"
    with pytest.raises(LinkError, match="请注明平台"):
        creator_service.resolve_ref("67194609689")
    with pytest.raises(LinkError, match="platform 指定"):
        creator_service.resolve_ref("https://space.bilibili.com/2/video", platform="douyin")


def test_resolve_ref_douyin_id_uses_provider_resolver(monkeypatch):
    ref = resolve_creator(
        "https://www.douyin.com/user/MS4wLjABAAAAEKnfa654JAJ_N5lgZDQluwsxmY0lhfmEYNQBBkwGG98"
    )
    seen = {}

    class Resolver:
        platform = Platform.DOUYIN

        def resolve(self, value, *, cookie_file):
            seen["value"] = value
            return ref

    monkeypatch.setattr(creator_service, "get_provider", lambda _platform: Resolver())
    monkeypatch.setattr(creator_service, "cookie_file_for", lambda _user, _platform: None)
    resolved = creator_service.resolve_ref("67194609689", platform="douyin")
    assert resolved == ref
    assert seen["value"] == "67194609689"


def test_douyin_provider_resolves_douyin_id_through_bridge(monkeypatch):
    monkeypatch.setattr(settings, "douyin_browser", True)
    sec = "MS4wLjABAAAAEKnfa654JAJ_N5lgZDQluwsxmY0lhfmEYNQBBkwGG98"

    class Bridge:
        def resolve_user(self, query, *, cookie_file):
            assert query == "67194609689"
            return {"sec_uid": sec, "unique_id": "67194609689", "nickname": "测试"}

    import json

    provider = DouyinCreatorProvider(
        client_factory=lambda **_kwargs: _FakeClient(200, json.dumps({"status_code": 1})),
        bridge_factory=Bridge,
    )
    ref = provider.resolve("67194609689", cookie_file=None)
    assert ref.platform is Platform.DOUYIN
    assert ref.creator_id == sec
    assert ref.url.endswith(sec)


def test_douyin_provider_resolves_douyin_id_via_public_user_info():
    import json

    sec = "MS4wLjABAAAAAR1hYk5la2AbZ_a-00wgh6UnypbV35hOG_8bQvsBqDrNGZpxcHBu7ueuCbAarO5l"
    payload = {
        "status_code": 0,
        "user_info": {
            "sec_uid": sec,
            "unique_id": "67194609689",
            "nickname": "小叶稚",
            "aweme_count": 259,
        },
    }
    provider = DouyinCreatorProvider(
        client_factory=lambda **_kwargs: _FakeClient(200, json.dumps(payload))
    )
    ref = provider.resolve("67194609689", cookie_file=None)
    assert ref.creator_id == sec
    assert ref.platform is Platform.DOUYIN


def test_douyin_window_sorts_by_publish_time_not_page_order(monkeypatch):
    monkeypatch.setattr(settings, "douyin_browser", True)
    ref = resolve_creator(
        "https://www.douyin.com/user/MS4wLjABAAAAEKnfa654JAJ_N5lgZDQluwsxmY0lhfmEYNQBBkwGG98"
    )

    class Bridge:
        def capture(self, _ref, *, need, cookie_file):
            old, new, mid = _aweme("old"), _aweme("new"), _aweme("mid")
            old["create_time"], new["create_time"], mid["create_time"] = 100, 300, 200
            return {"profile": old["author"], "items": [old, new, mid], "has_more": False}

    provider = DouyinCreatorProvider(
        client_factory=lambda **_kwargs: _FakeClient(403, "Blocked by ArgusSecurityPlugin"),
        bridge_factory=Bridge,
    )
    window = provider.fetch_window(ref, start=1, count=2, cookie_file=None)
    assert [video.video_id for video in window.items] == ["new", "mid"]
