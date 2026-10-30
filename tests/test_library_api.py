from uuid import uuid4

from fastapi.testclient import TestClient

from getvideo.api import app
from getvideo.accounts import _windows
from getvideo.db import init_db, next_updated_seq, session_scope
from getvideo.models import ExtractJob, JobStatus, Track


def register(client):
    _windows.clear()
    phone = "139" + str(int(uuid4().hex[:10], 16))[-8:].zfill(8)
    response = client.post("/account/register", json={"phone": phone, "password": "test-password-123"})
    assert response.status_code == 200, response.text
    data = response.json()
    return data["user_id"], {"X-GetVideo-Session": data["session_token"]}


def make_track(owner: str, *, video_id: str | None = None) -> int:
    init_db()
    with session_scope() as session:
        track = Track(
            owner_scope=owner,
            platform="bilibili",
            video_id=video_id or ("lib-" + uuid4().hex),
            title="原始标题",
            uploader="原始 UP",
            source_url="https://www.bilibili.com/video/BV1ujNV6qEXg",
            audio_path="missing.mp4",
            categories_json='["其他"]',
            updated_seq=next_updated_seq(session),
        )
        session.add(track)
        session.flush()
        return track.id


def test_track_read_patch_delete_restore_and_isolation():
    init_db()
    with TestClient(app) as client:
        alice, alice_headers = register(client)
        bob, bob_headers = register(client)
        track_id = make_track(alice)

        assert client.get("/v2/tracks/{}".format(track_id)).status_code == 401
        detail = client.get(f"/v2/tracks/{track_id}", headers=alice_headers)
        assert detail.status_code == 200 and detail.json()["title"] == "原始标题"
        assert client.get(f"/v2/tracks/{track_id}", headers=bob_headers).status_code == 404

        patched = client.patch(
            f"/v2/tracks/{track_id}",
            headers=alice_headers,
            json={"title": "新标题", "uploader": "新 UP", "categories": ["治愈"]},
        )
        assert patched.status_code == 200, patched.text
        body = patched.json()
        assert body["title"] == "新标题"
        assert body["uploader"] == "新 UP"
        assert body["categories"] == ["治愈"]
        assert body["classify_stage"] == "manual"

        deleted = client.delete(f"/v2/tracks/{track_id}", headers=alice_headers)
        assert deleted.status_code == 200 and deleted.json()["deleted"] is True
        assert client.get(f"/v2/tracks/{track_id}", headers=alice_headers).json()["deleted"] is True

        restored = client.post(f"/v2/tracks/{track_id}/restore", headers=alice_headers)
        assert restored.status_code == 200 and restored.json()["deleted"] is False


def test_resolve_and_extraction_jobs():
    init_db()
    with TestClient(app) as client:
        alice, headers = register(client)
        resolved = client.post(
            "/v2/resolve",
            headers=headers,
            json={"text": "https://www.bilibili.com/video/BV1ujNV6qEXg"},
        )
        assert resolved.status_code == 200, resolved.text
        assert resolved.json()["platform"] == "bilibili"
        assert resolved.json()["video_id"] == "BV1ujNV6qEXg"

        invalid = client.post("/v2/resolve", headers=headers, json={"text": "这里没有链接"})
        assert invalid.status_code == 400

        with session_scope() as session:
            session.add(
                ExtractJob(
                    user_id=alice,
                    raw_input="https://www.bilibili.com/video/BV1ujNV6qEXg",
                    platform="bilibili",
                    video_id="BV1ujNV6qEXg",
                    status=JobStatus.DONE,
                )
            )
        jobs = client.get("/v2/extraction-jobs", headers=headers)
        assert jobs.status_code == 200
        assert jobs.json()[0]["video_id"] == "BV1ujNV6qEXg"


def test_maintenance_and_scheduler_are_owner_scoped():
    init_db()
    with TestClient(app) as client:
        alice, headers = register(client)
        bob, _ = register(client)
        make_track(alice, video_id="alice-maint")
        make_track(bob, video_id="bob-maint")

        result = client.post("/v2/maintenance/reclassify?force=true", headers=headers)
        assert result.status_code == 200, result.text
        assert result.json()["updated"] >= 1

        assert client.get("/v2/maintenance/duplicates", headers=headers).status_code == 200
        assert client.post("/v2/maintenance/repair-audio-sizes", headers=headers).status_code == 200
        assert client.post("/v2/maintenance/fingerprints/backfill", headers=headers).status_code == 200

        scheduler = client.get("/v2/scheduler", headers=headers)
        assert scheduler.status_code == 200
        assert "groups" in scheduler.json()
        assert all("keyword_count" in group and "keywords" not in group for group in scheduler.json()["groups"])
        # 当前 config/keywords.yaml 全是全局组，不归任何账号；不能由普通账号触发。
        assert client.post("/v2/scheduler/run-once", headers=headers).status_code == 400
