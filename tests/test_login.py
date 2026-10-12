"""扫码登录流程的单测。平台接口打桩，不触网。"""
from __future__ import annotations

import pytest

from getvideo import credentials as cr
from getvideo import login
from getvideo.db import init_db, session_scope
from getvideo.login.base import LoginError, QrPollResult, QrState, QrTicket
from getvideo.models import PlatformCredential


class _FakeProvider:
    platform = "bilibili"

    def __init__(self) -> None:
        self.script: list[QrPollResult] = []
        self.polls = 0

    def start(self) -> QrTicket:
        return QrTicket(platform="bilibili", key="k1", qr_url="https://example.com/qr", expires_in=180)

    def poll(self, key: str) -> QrPollResult:
        self.polls += 1
        return self.script.pop(0) if self.script else QrPollResult(QrState.WAITING, "未扫码")


@pytest.fixture
def fake(monkeypatch):
    init_db()
    with session_scope() as s:
        s.query(PlatformCredential).filter(PlatformCredential.user_id.like("test_%")).delete(
            synchronize_session=False
        )
    p = _FakeProvider()
    monkeypatch.setitem(login._PROVIDERS, "bilibili", p)
    yield p


def test_start_returns_session(fake):
    s = login.start("test_u", "bilibili")
    assert s.qr_url == "https://example.com/qr"
    assert s.state is QrState.WAITING
    assert s.session_id


def test_poll_progresses_through_states(fake):
    fake.script = [
        QrPollResult(QrState.SCANNED, "已扫码"),
        QrPollResult(QrState.CONFIRMED, "ok", {"SESSDATA": "abc", "bili_jct": "d"}),
    ]
    s = login.start("test_u", "bilibili")
    assert login.poll(s.session_id).state is QrState.SCANNED
    assert login.poll(s.session_id).state is QrState.CONFIRMED


def test_confirmed_saves_encrypted_credential(fake):
    fake.script = [QrPollResult(QrState.CONFIRMED, "ok", {"SESSDATA": "abc"})]
    s = login.start("test_u", "bilibili")
    login.poll(s.session_id)
    assert cr.load("test_u", "bilibili") == {"SESSDATA": "abc"}


def test_terminal_state_stops_hitting_platform(fake):
    """确认之后不该继续打平台接口 —— 白白消耗配额还可能触发风控。"""
    fake.script = [QrPollResult(QrState.CONFIRMED, "ok", {"SESSDATA": "abc"})]
    s = login.start("test_u", "bilibili")
    login.poll(s.session_id)
    before = fake.polls
    login.poll(s.session_id)
    login.poll(s.session_id)
    assert fake.polls == before


def test_expired_session_by_clock(fake, monkeypatch):
    import time as _t

    s = login.start("test_u", "bilibili")
    monkeypatch.setattr(_t, "time", lambda: s.created_at + 9999)
    monkeypatch.setattr(login.time, "time", lambda: s.created_at + 9999)
    assert login.poll(s.session_id).state is QrState.EXPIRED


def test_unknown_session_raises(fake):
    with pytest.raises(LoginError):
        login.poll("nope")


def test_unsupported_platform_raises(fake):
    with pytest.raises(LoginError):
        login.start("test_u", "youtube")


# ---------- 凭证导入 ----------


def test_import_requires_key_field(fake):
    with pytest.raises(LoginError, match="SESSDATA"):
        login.import_cookies("test_u", "bilibili", "foo=bar")


def test_import_rejects_empty(fake):
    with pytest.raises(LoginError):
        login.import_cookies("test_u", "bilibili", "   ")


def test_import_saves(fake):
    r = login.import_cookies("test_u", "bilibili", "SESSDATA=abc; bili_jct=def")
    assert r["saved_keys"] == ["SESSDATA", "bili_jct"]
    assert cr.load("test_u", "bilibili") == {"SESSDATA": "abc", "bili_jct": "def"}
