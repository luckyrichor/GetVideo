from uuid import uuid4
from fastapi.testclient import TestClient
from getvideo.api import app
from getvideo.accounts import _windows
from getvideo.db import session_scope
from getvideo.models import Track
from getvideo.config import settings


def register(c):
    _windows.clear()
    phone = '139' + str(int(uuid4().hex[:10], 16))[-8:].zfill(8)
    r = c.post('/account/register', json={'phone': phone, 'password': 'test-password-123'})
    assert r.status_code == 200
    d = r.json()
    return d['user_id'], {'X-GetVideo-Session': d['session_token']}


def test_v2_auth_and_private_tracks():
    with TestClient(app) as c:
        assert c.get('/v2/sync').status_code == 401
        alice, a = register(c); bob, b = register(c)
        f = settings.audio_dir / 'private-test.audio'; f.write_bytes(b'private audio')
        with session_scope() as s:
            t = Track(owner_scope=alice, platform='douyin', video_id=uuid4().hex,
                      source_url='https://example.invalid/', audio_path=f.name, updated_seq=9999)
            s.add(t); s.flush(); tid = t.id
        assert [t['id'] for t in c.get('/v2/sync', headers=a).json()['items']] == [tid]
        assert c.get('/v2/sync', headers=b).json()['items'] == []
        assert c.get(f'/v2/video/{tid}', headers=b).status_code == 404
        assert c.get(f'/v2/video/{tid}', headers=a).content == b'private audio'
        assert c.post('/v2/extract', headers=a, json={'text': 'bad', 'user_id': bob}).status_code == 422
        c.post('/account/logout', headers=a)
        assert c.get('/v2/sync', headers=a).status_code == 401


def test_qr_ownership_checked_before_platform_poll(monkeypatch):
    from getvideo import login
    from getvideo.login.base import QrState
    import time
    with TestClient(app) as c:
        alice, a = register(c); _, b = register(c)
        q = login.QrSession('owned-test-qr', alice, 'bilibili', 'key', 'https://example.invalid/qr', time.time(), 180)
        login._sessions[q.session_id] = q
        calls = []
        monkeypatch.setattr(login._PROVIDERS['bilibili'], 'poll', lambda _: calls.append(True))
        assert c.get('/v2/platforms/qr/owned-test-qr', headers=b).status_code == 404
        assert c.get('/v2/platforms/qr/owned-test-qr/image', headers=b).status_code == 404
        assert calls == []
        assert c.get('/v2/platforms/qr/owned-test-qr/image', headers=a).status_code == 200
        c.delete('/v2/platforms/credentials/bilibili', headers=a)
        assert login.poll(q.session_id, owner=alice).state is QrState.FAILED
        assert calls == []
