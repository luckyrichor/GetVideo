import fcntl
from types import SimpleNamespace

import pytest
from pydantic import ValidationError

from getvideo import scheduler as w
from getvideo.config import settings
from getvideo.db import init_db, session_scope
from getvideo.models import CrawlState


@pytest.fixture(autouse=True)
def setup():
    init_db()
    with session_scope() as s:
        s.query(CrawlState).delete()


@pytest.mark.parametrize("value", ["0h", "-1h", "1s", "abc", ""])
def test_invalid_interval(value):
    with pytest.raises(ValidationError):
        w.Group(name="test", interval=value, keywords=["钢琴"])


def test_config_validation(tmp_path):
    path = tmp_path / "keywords.yaml"
    path.write_text("groups:\n  - name: test\n    urls: [https://example.com]\n    interval: 30m\n")
    assert w.load_schedule(path).groups[0].seconds == 1800
    with pytest.raises(ValidationError):
        w.Schedule(groups=[w.Group(name="same", urls=["x"])] * 2)


def test_group_cap_dedupe_and_failure_isolation(monkeypatch):
    monkeypatch.setattr(w, "search_bilibili", lambda k, n, u: ["one", "two", "three"])
    seen = []
    def extract(url, **kwargs):
        seen.append(url)
        if url == "two":
            raise RuntimeError("failure")
        return SimpleNamespace(reused=url == "one")
    monkeypatch.setattr(w, "extract", extract)
    result = w.run_group(w.Group(name="test", keywords=["a", "b"], urls=["one"], top_n=3))
    assert seen == ["one", "two", "three"]
    assert (result["added"], result["reused"], result["failed"]) == (1, 1, 1)


def test_schedule_persistence_retry_disable_and_force(monkeypatch):
    schedule = w.Schedule(groups=[w.Group(name="test", urls=["one"], interval="1h"),
                                  w.Group(name="off", urls=["two"], enabled=False)])
    failed = {"name": "test", "failed": 1}
    monkeypatch.setattr(w, "run_group", lambda g: failed)
    assert w.tick(schedule, now=1000) == [failed]
    assert w.tick(schedule, now=1299) == []
    success = {"name": "test", "failed": 0}
    monkeypatch.setattr(w, "run_group", lambda g: success)
    assert w.tick(schedule, now=1300) == [success]
    assert w.tick(schedule, now=1301) == []
    assert w.tick(schedule, now=1301, force=True) == [success]
    with session_scope() as s:
        assert s.get(CrawlState, "test").next_run == 4901
        assert s.get(CrawlState, "off") is None


def test_worker_lock(monkeypatch):
    schedule = w.Schedule(groups=[w.Group(name="test", urls=["one"])])
    monkeypatch.setattr(w, "run_group", lambda g: pytest.fail("worker must not overlap"))
    with (settings.tmp_dir / "scheduler.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        assert w.tick(schedule) == []
