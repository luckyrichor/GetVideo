import json

import httpx
from fastapi.testclient import TestClient

from getvideo import agent
from getvideo.api import app
from getvideo.config import settings
from getvideo.db import init_db, session_scope
from getvideo.models import AgentMemoryVersion, Track


def test_memory_is_explicit_user_scoped_and_revision_checked(monkeypatch):
    monkeypatch.setattr(settings, "ark_api_key", "")
    init_db()
    with TestClient(app) as client:
        created = client.post("/agent/memories", json={"user_id": "alice", "content": "喜欢古风"})
        assert created.status_code == 200
        mid = created.json()["id"]
        assert client.get("/agent/memories/bob").json() == []
        assert client.put(f"/agent/memories/alice/{mid}", json={"content": "喜欢钢琴", "expected_revision": 1}).json()["revision"] == 2
        assert client.put(f"/agent/memories/alice/{mid}", json={"content": "喜欢摇滚", "expected_revision": 1}).status_code == 409
        assert client.delete(f"/agent/memories/bob/{mid}").status_code == 404
        assert client.get("/agent/memories/alice").json()[0]["content"] == "喜欢钢琴"
        assert client.delete(f"/agent/memories/alice/{mid}").status_code == 200
        assert client.get("/agent/memories/alice").json() == []
    with session_scope() as session:
        history = list(session.query(AgentMemoryVersion).filter_by(memory_id=mid).order_by(AgentMemoryVersion.revision))
        assert [(v.revision, v.action, v.content) for v in history] == [
            (1, "create", "喜欢古风"), (2, "update", "喜欢钢琴"), (3, "delete", None)]


def test_agent_uses_library_and_rejects_hallucinated_track_ids(monkeypatch):
    monkeypatch.setattr(settings, "ark_api_key", "test-key")
    monkeypatch.setattr(settings, "ark_models", "first,second")
    init_db()
    with session_scope() as session:
        session.add(Track(platform="douyin", video_id="agent-test", title="治愈钢琴",
                          source_url="https://example.com", audio_path="test", updated_seq=100))
    attempts = []

    def post(url, **kwargs):
        model = kwargs["json"]["model"]
        attempts.append(model)
        assert kwargs["headers"]["Authorization"] == "Bearer test-key"
        payload = json.loads(kwargs["json"]["messages"][1]["content"])
        assert any(t["title"] == "治愈钢琴" for t in payload["tracks"])
        assert "喜欢治愈" in payload["music_preferences"]
        track_id = 999999 if model == "first" else next(t["id"] for t in payload["tracks"] if t["title"] == "治愈钢琴")
        return httpx.Response(200, request=httpx.Request("POST", url), json={"choices": [
            {"message": {"content": json.dumps({"answer": "推荐治愈钢琴", "track_ids": [track_id]})}}]})

    monkeypatch.setattr(agent.httpx, "post", post)
    with TestClient(app) as client:
        assert client.post("/agent/memories", json={"user_id": "music-user", "content": "喜欢治愈"}).status_code == 200
        result = client.post("/agent/chat", json={"user_id": "music-user", "message": "推荐钢琴"})
    assert result.status_code == 200
    assert result.json()["model"] == "second"
    assert attempts == ["first", "second"]
    assert len(result.json()["track_ids"]) == 1


def test_agent_without_key_has_honest_fallback(monkeypatch):
    monkeypatch.setattr(settings, "ark_api_key", "")
    init_db()
    with TestClient(app) as client:
        result = client.post("/agent/chat", json={"user_id": "new-user", "message": "推荐歌曲"})
    assert result.status_code == 200
    assert result.json()["model"] is None
    assert "模型暂不可用" in result.json()["answer"] or "视频库" in result.json()["answer"]


def test_agent_can_recommend_phone_only_track(monkeypatch):
    monkeypatch.setattr(settings, "ark_api_key", "")
    init_db()
    with TestClient(app) as client:
        result = client.post("/agent/chat", json={"user_id": "phone-user", "message": "有什么可听的？",
            "local_tracks": [{"id": -123, "title": "手机直提取的歌曲", "artist": "测试作者",
                              "platform": "douyin", "duration_sec": 180, "categories": []}]})
        invalid = client.post("/agent/chat", json={"user_id": "phone-user", "message": "推荐",
            "local_tracks": [{"id": 123, "title": "伪造的本地歌曲", "platform": "douyin"}]})
    assert result.status_code == 200
    assert -123 in result.json()["track_ids"]
    assert "手机直提取的歌曲" in result.json()["answer"]
    assert invalid.status_code == 422
