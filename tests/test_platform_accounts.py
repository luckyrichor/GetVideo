"""Provider login cannot register accounts, spoof identity, or cross phone ownership."""
from uuid import uuid4
from datetime import timedelta

import httpx
import pytest
from fastapi.testclient import TestClient
from sqlalchemy import delete, select, func

from getvideo.api import app
from getvideo import platform_accounts as pa
from getvideo.accounts import _windows, token_hash
from getvideo.db import session_scope
from getvideo.models import AccountPlatformAttempt as Attempt, AccountPlatformBinding as Binding, AppUser, PlatformCredential, utcnow
from getvideo.login.base import QrPollResult, QrState, QrTicket


@pytest.fixture
def flow(monkeypatch):
    _windows.clear()
    with session_scope() as session:
        session.execute(delete(Attempt)); session.execute(delete(Binding))
    class Provider:
        def start(self): return QrTicket("bilibili", uuid4().hex, "https://passport.bilibili.com/test-qr", 180)
        def poll(self, key): return QrPollResult(QrState.CONFIRMED, "ok", {"SESSDATA": "private-cookie", "DedeUserID": "untrusted"})
    monkeypatch.setattr(pa, "BilibiliLogin", Provider)
    identity = {"id": "42", "name": "test-account"}
    monkeypatch.setattr(pa, "verify_bilibili", lambda _: (identity["id"], identity["name"]))
    monkeypatch.setattr(pa.settings, "douyin_client_key", "")
    monkeypatch.setattr(pa.settings, "douyin_client_secret", "")
    monkeypatch.setattr(pa.settings, "douyin_redirect_uri", "")
    with TestClient(app) as client: yield client, identity


def register(c):
    phone = '139' + str(int(uuid4().hex[:10], 16))[-8:].zfill(8)
    r = c.post('/account/register', json={"phone": phone, "password": "test-password-123"})
    assert r.status_code == 200
    return r.json(), {"X-GetVideo-Session": r.json()["session_token"]}


def attempt(c, mode, headers=None, platform='bilibili'):
    r = c.post('/account/platform-auth/start', headers=headers or {}, json={"platform": platform, "mode": mode})
    assert r.status_code == 200, r.text
    data = r.json()
    return {"attempt_id": data['attempt_id'], "poll_token": data['poll_token']}


def verified(c, proof, h=None):
    r = c.post('/account/platform-auth/status', json=proof, headers=h or {})
    assert r.status_code == 200, r.text
    assert r.json()['status'] == 'verified'


def complete(c, proof, h=None):
    return c.post('/account/platform-auth/complete', json=proof, headers=h or {})


def test_bind_requires_phone_login_and_rejects_client_identity_fields(flow):
    c, _ = flow
    assert c.post('/account/platform-auth/start', json={'platform': 'bilibili', 'mode': 'bind'}).status_code == 401
    _, h = register(c)
    assert c.post('/account/platform-auth/start', headers=h, json={'platform': 'bilibili', 'mode': 'bind', 'user_id': 'other'}).status_code == 422


def test_unbound_platform_cannot_create_phone_account(flow):
    c, _ = flow
    with session_scope() as s: before = s.scalar(select(func.count()).select_from(AppUser))
    p = attempt(c, 'login'); verified(c, p)
    r = complete(c, p)
    assert r.status_code == 403 and '手机号' in r.json()['detail']
    with session_scope() as s: assert s.scalar(select(func.count()).select_from(AppUser)) == before


def test_bound_login_returns_same_phone_and_consumes_proof(flow):
    c, _ = flow
    phone, h = register(c)
    p = attempt(c, 'bind', h); verified(c, p, h)
    result = complete(c, p, h)
    assert result.status_code == 200 and result.json()['session'] is None
    assert complete(c, p, h).status_code == 409
    with session_scope() as s:
        cred = s.scalar(select(PlatformCredential).where(PlatformCredential.user_id == phone['user_id'], PlatformCredential.platform == 'bilibili'))
        assert cred and b'private-cookie' not in cred.cookie_blob
    c.post('/account/logout', headers=h)
    p = attempt(c, 'login'); verified(c, p)
    r = complete(c, p)
    assert r.status_code == 200, r.text
    assert r.json()['session']['user_id'] == phone['user_id']
    assert r.json()['session']['phone'] == phone['phone']
    assert complete(c, p).status_code == 409
    session = r.json()['session']
    assert c.get('/account/me', headers={'X-GetVideo-Session': session['session_token']}).json()['user_id'] == phone['user_id']


def test_platform_account_cannot_move_to_other_phone_and_no_silent_replace(flow):
    c, identity = flow
    _, a = register(c); _, b = register(c)
    p = attempt(c, 'bind', a); verified(c, p, a); assert complete(c, p, a).status_code == 200
    p = attempt(c, 'bind', b); verified(c, p, b); assert complete(c, p, b).status_code == 409
    identity['id'] = '99'
    p = attempt(c, 'bind', a); verified(c, p, a); assert complete(c, p, a).status_code == 409


def test_poll_secret_owner_logout_expiry_and_cancellation(flow):
    c, _ = flow
    _, a = register(c); _, b = register(c)
    p = attempt(c, 'bind', a)
    assert c.post('/account/platform-auth/status', json={**p, 'poll_token': 'x'*43}, headers=a).status_code == 404
    assert c.post('/account/platform-auth/status', json=p, headers=b).status_code == 403
    assert complete(c, p, a).status_code == 409
    assert c.post('/account/platform-auth/image', json=p, headers=a).headers['content-type'] == 'image/png'
    c.post('/account/platform-auth/cancel', json=p, headers=a)
    assert complete(c, p, a).status_code == 409
    p = attempt(c, 'bind', a); verified(c, p, a)
    c.post('/account/logout', headers=a)
    assert complete(c, p, a).status_code == 401
    p = attempt(c, 'login')
    with session_scope() as s: s.get(Attempt, token_hash(p['attempt_id'])).expires_at = utcnow() - timedelta(seconds=1)
    assert complete(c, p).status_code == 410


def test_unbind_without_password_requires_session_and_prevents_future_platform_login(flow):
    c, _ = flow
    _, h = register(c)
    p = attempt(c, 'bind', h); verified(c, p, h); complete(c, p, h)
    waiting = attempt(c, 'bind', h)
    path = '/account/platform-bindings/bilibili/unbind'
    assert c.post(path).status_code == 401
    assert c.post(path, headers=h).status_code == 200
    assert c.get('/account/platform-bindings', headers=h).json() == []
    assert c.post('/account/platform-auth/status', json=waiting, headers=h).json()['status'] == 'failed'
    p = attempt(c, 'login'); verified(c, p); assert complete(c, p).status_code == 403


def test_douyin_config_and_state_callback_are_fail_closed(flow, monkeypatch):
    c, _ = flow
    assert c.get('/account/platforms').json()[1]['available'] is False
    assert c.post('/account/platform-auth/start', json={'platform': 'douyin', 'mode': 'login'}).status_code == 503
    monkeypatch.setattr(pa.settings, 'douyin_client_key', 'app')
    monkeypatch.setattr(pa.settings, 'douyin_client_secret', 'private-secret')
    monkeypatch.setattr(pa.settings, 'douyin_redirect_uri', 'https://example.com/account/platform-auth/douyin/callback')
    monkeypatch.setattr(pa, 'exchange_douyin', lambda code: ('douyin-subject', '抖音测试'))
    phone, h = register(c)
    p = attempt(c, 'bind', h, 'douyin')
    bad = c.get('/account/platform-auth/douyin/callback', params={'state': 'x'*43, 'code': 'code'})
    assert bad.status_code == 400
    r = c.get('/account/platform-auth/douyin/callback', params={'state': p['attempt_id'], 'code': 'code'})
    assert r.status_code == 200 and 'session_token' not in r.text and phone['phone'] not in r.text
    assert r.headers['referrer-policy'] == 'no-referrer'
    assert c.get('/account/platform-auth/douyin/callback', params={'state': p['attempt_id'], 'code': 'code'}).status_code == 409
    assert complete(c, p, h).status_code == 200
    p = attempt(c, 'login', platform='douyin')
    c.get('/account/platform-auth/douyin/callback', params={'state': p['attempt_id'], 'code': 'code'})
    assert complete(c, p).json()['session']['user_id'] == phone['user_id']


def test_bilibili_identity_is_verified_on_server_not_cookie_id(monkeypatch):
    original = httpx.Client
    monkeypatch.setattr(pa.httpx, 'Client', lambda **kw: original(transport=httpx.MockTransport(lambda req: httpx.Response(200,
        json={'code': 0, 'data': {'isLogin': True, 'mid': 42, 'uname': 'verified'}})), **kw))
    assert pa.verify_bilibili({'SESSDATA': 'secret', 'DedeUserID': '999'}) == ('42', 'verified')


def test_douyin_exchange_uses_server_secret_and_verified_subject(monkeypatch):
    monkeypatch.setattr(pa.settings, 'douyin_client_key', 'client')
    monkeypatch.setattr(pa.settings, 'douyin_client_secret', 'secret')
    original = httpx.Client
    def transport(request):
        if request.url.path == '/oauth/access_token/':
            assert request.method == 'POST' and b'client_secret=secret' in request.content and not request.url.query
            return httpx.Response(200, json={'data': {'access_token': 'private', 'open_id': 'subject', 'error_code': 0}})
        assert request.headers['access-token'] == 'private'
        return httpx.Response(200, json={'data': {'open_id': 'subject', 'nickname': 'verified', 'error_code': 0}})
    monkeypatch.setattr(pa.httpx, 'Client', lambda **kw: original(transport=httpx.MockTransport(transport), **kw))
    assert pa.exchange_douyin('code') == ('subject', 'verified')


def test_callback_access_log_redacts_code_and_state():
    import logging
    from getvideo.api import PlatformCallbackAccessFilter
    target = '/account/platform-auth/douyin/callback?code=private-code&state=private-state'
    record = logging.LogRecord('uvicorn.access', logging.INFO, '', 0, '%s %s %s %s %s', ('client', 'GET', target, '1.1', 200), None)
    assert PlatformCallbackAccessFilter().filter(record)
    assert 'private-code' not in record.getMessage() and 'private-state' not in record.getMessage()
    assert '/account/platform-auth/douyin/callback' in record.getMessage()


def test_other_phone_cannot_unbind_owner_binding(flow):
    c, _ = flow
    _, owner = register(c); _, other = register(c)
    p = attempt(c, 'bind', owner); verified(c, p, owner)
    assert complete(c, p, owner).status_code == 200
    assert c.post('/account/platform-bindings/bilibili/unbind', headers=other).status_code == 200
    assert len(c.get('/account/platform-bindings', headers=owner).json()) == 1
    assert c.get('/account/platform-bindings', headers=other).json() == []


def test_platform_can_bind_another_phone_after_owner_unbinds(flow):
    c, _ = flow
    first, owner = register(c); second, other = register(c)
    p = attempt(c, 'bind', owner); verified(c, p, owner)
    assert complete(c, p, owner).status_code == 200
    p = attempt(c, 'bind', other); verified(c, p, other)
    assert complete(c, p, other).status_code == 409
    assert c.post('/account/platform-bindings/bilibili/unbind', headers=owner).status_code == 200
    assert complete(c, p, other).status_code == 200
    p = attempt(c, 'login'); verified(c, p)
    logged_in = complete(c, p).json()['session']
    assert logged_in['user_id'] == second['user_id']
    assert logged_in['phone'] == second['phone'] != first['phone']
