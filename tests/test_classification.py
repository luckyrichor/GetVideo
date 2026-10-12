import json

import httpx
import pytest

from getvideo import classification as c
from getvideo.config import settings
from getvideo.extractors.base import MediaInfo
from getvideo.sharelink import Platform


def info(title):
    return MediaInfo(platform=Platform.BILIBILI, video_id="test", title=title, webpage_url="")


@pytest.fixture(autouse=True)
def no_model(monkeypatch):
    monkeypatch.setattr(settings, "ollama_model", "")
    monkeypatch.setattr(settings, "ark_api_key", "")


@pytest.mark.parametrize("title,categories,vocal", [
    ("治愈钢琴纯音乐", ["治愈", "钢琴"], False),
    ("热血摇滚翻唱", ["热血", "摇滚"], True),
    ("无人声", ["其他"], False),
    ("标题", ["其他"], None),
    ("纯音乐与人声演唱对比", ["其他"], None),
])
def test_rules(title, categories, vocal):
    result = c.classify(info(title))
    assert result.categories == categories
    assert result.has_vocal is vocal
    assert result.stage == "rules"


def test_model_response(monkeypatch):
    monkeypatch.setattr(settings, "ollama_model", "test-model")
    def post(url, **kwargs):
        assert url.endswith("/api/chat")
        assert kwargs["json"]["stream"] is False
        assert kwargs["json"]["model"] == "test-model"
        return httpx.Response(200, request=httpx.Request("POST", url), json={
            "message": {"content": json.dumps({"categories": ["治愈", "治愈"], "has_vocal": None})}})
    monkeypatch.setattr(c.httpx, "post", post)
    result = c.classify(info("sample"))
    assert result.stage == "ai"
    assert result.categories == ["治愈"]
    assert result.has_vocal is None


@pytest.mark.parametrize("content", ["not-json", '{"categories": ["不存在"]}',
                                      '{"categories": ["治愈"], "has_vocal": "false"}'])
def test_invalid_model_response_falls_back(monkeypatch, content):
    monkeypatch.setattr(settings, "ollama_model", "test")
    monkeypatch.setattr(c.httpx, "post", lambda url, **kw: httpx.Response(
        200, request=httpx.Request("POST", url), json={"message": {"content": content}}))
    assert c.classify(info("治愈")).stage == "rules"


def test_model_timeout_falls_back(monkeypatch):
    monkeypatch.setattr(settings, "ollama_model", "test")
    def timeout(*a, **kw):
        raise httpx.ReadTimeout("test")
    monkeypatch.setattr(c.httpx, "post", timeout)
    assert c.classify(info("治愈")).categories == ["治愈"]


def test_ark_tries_next_model_and_validates_result(monkeypatch):
    monkeypatch.setattr(settings, "ark_api_key", "test-key")
    monkeypatch.setattr(settings, "ark_models", "first,second")
    called = []

    def post(url, **kwargs):
        model = kwargs["json"]["model"]
        called.append(model)
        assert kwargs["headers"]["Authorization"] == "Bearer test-key"
        assert kwargs["json"]["response_format"]["type"] == "json_schema"
        if model == "first":
            return httpx.Response(404, request=httpx.Request("POST", url), json={"error": {"code": "ModelNotOpen"}})
        return httpx.Response(200, request=httpx.Request("POST", url), json={
            "choices": [{"message": {"content": json.dumps({"categories": ["钢琴", "钢琴"], "has_vocal": None})}}]})

    monkeypatch.setattr(c.httpx, "post", post)
    result = c.classify(info("sample"))
    assert called == ["first", "second"]
    assert result.categories == ["钢琴"]
    assert result.stage == "ai"


def test_ark_unavailable_falls_back_to_rules(monkeypatch):
    monkeypatch.setattr(settings, "ark_api_key", "test-key")
    monkeypatch.setattr(settings, "ark_models", "first,second")
    monkeypatch.setattr(c.httpx, "post", lambda url, **kw: httpx.Response(
        429, request=httpx.Request("POST", url)))
    assert c.classify(info("治愈钢琴")).categories == ["治愈", "钢琴"]


def test_backfill_updates_sync_only_when_changed():
    from getvideo.db import init_db, session_scope, next_updated_seq
    from getvideo.models import Track
    init_db()
    with session_scope() as s:
        s.add(Track(platform="bilibili", video_id="classify-test", title="治愈钢琴", source_url="",
                    audio_path="test", updated_seq=next_updated_seq(s)))
    assert c.reclassify(limit=1000) >= 1
    with session_scope() as s:
        row = s.query(Track).filter_by(video_id="classify-test").one()
        seq = row.updated_seq
        assert json.loads(row.categories_json) == ["治愈", "钢琴"]
    c.reclassify(limit=1000)
    with session_scope() as s:
        assert s.query(Track).filter_by(video_id="classify-test").one().updated_seq == seq
