import json
from uuid import uuid4

import httpx
import pytest
from fastapi.testclient import TestClient
from sqlalchemy import select

from getvideo.api import app
from getvideo.accounts import _windows
from getvideo.config import settings
from getvideo.db import session_scope
from getvideo.models import AppUser, AppSession, AgentEvent, AgentMemory, ExtractJob, JobStatus, Track
from getvideo import extraction_agent


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setattr(settings, 'ark_api_key', '')
    _windows.clear()
    with TestClient(app) as c:
        yield c


def register(client, phone=None):
    phone = phone or '139' + str(int(uuid4().hex[:10], 16))[-8:].zfill(8)
    response = client.post('/account/register', json={'phone': phone, 'password': 'test-password-123'})
    assert response.status_code == 200, response.text
    data = response.json()
    return data, {'X-GetVideo-Session': data['session_token']}


def ask(client, headers, text='提取 https://v.douyin.com/test/', cid=None, request_id=None):
    body = {'request_id': request_id or str(uuid4()), 'message': text}
    if cid:
        body['conversation_id'] = cid
    return client.post('/agent/v2/chat', headers=headers, json=body)


def test_phone_is_unique_and_password_token_are_not_plaintext(client):
    data, headers = register(client, '13800138001')
    duplicate = client.post('/account/register', json={'phone': '+86 13800138001', 'password': 'test-password-123'})
    assert duplicate.status_code == 409
    bad = client.post('/account/login', json={'phone': '13800138001', 'password': 'wrong-password'})
    assert bad.status_code == 401
    login = client.post('/account/login', json={'phone': '008613800138001', 'password': 'test-password-123'})
    assert login.json()['user_id'] == data['user_id']
    with session_scope() as session:
        user = session.get(AppUser, data['user_id'])
        assert user.password_hash.startswith('scrypt$')
        assert 'test-password' not in user.password_hash
        assert session.get(AppSession, data['session_token']) is None
    assert client.get('/account/me', headers=headers).status_code == 200
    assert client.post('/account/logout', headers=headers).status_code == 200
    assert client.get('/account/me', headers=headers).status_code == 401


def test_registered_identity_cannot_be_spoofed_through_legacy_endpoints(client):
    alice, a = register(client)
    bob, b = register(client)
    path = '/agent/memories/' + alice['user_id']
    assert client.get(path).status_code == 401
    assert client.get(path, headers=b).status_code == 403
    assert client.post('/agent/memories', headers=b, json={'user_id': alice['user_id'], 'content': '喜欢古风'}).status_code == 403
    memory = client.post('/agent/memories', headers=a, json={'user_id': alice['user_id'], 'content': '喜欢古风'})
    assert memory.status_code == 200
    assert client.get('/agent/memories/' + bob['user_id'], headers=b).json() == []
    assert client.post('/extract', headers=b, json={'text': 'bad', 'user_id': alice['user_id']}).status_code == 403
    assert client.get('/credentials/' + alice['user_id'], headers=b).status_code == 403


def test_conversation_action_requires_owner_confirmation_and_results_are_idempotent(client):
    alice, a = register(client)
    bob, b = register(client)
    request_id = str(uuid4())
    response = ask(client, a, request_id=request_id)
    assert response.status_code == 200, response.text
    data = response.json()
    action = data['action']; cid = data['conversation_id']
    assert action['status'] == 'pending'
    assert ask(client, a, request_id=request_id).json() == data
    assert ask(client, a, text='different', request_id=request_id).status_code == 409
    assert client.get('/agent/v2/conversations/' + cid, headers=b).status_code == 404
    assert ask(client, b, cid=cid).status_code == 404
    endpoint = '/agent/v2/actions/' + action['id']
    result = {'success': True, 'track_id': -42, 'title': '手机视频', 'diagnostic': '已加入本地库'}
    assert client.post(endpoint + '/result', json=result, headers=a).status_code == 409
    assert client.post(endpoint + '/claim', json={'mode': 'server'}, headers=b).status_code == 404
    assert client.post(endpoint + '/claim', json={'mode': 'phone'}, headers=a).json()['status'] == 'running'
    assert client.post(endpoint + '/claim', json={'mode': 'phone'}, headers=a).status_code == 409
    done = client.post(endpoint + '/result', json=result, headers=a)
    assert done.status_code == 200
    assert done.json()['result']['source'] == 'device_reported'
    assert client.post(endpoint + '/result', json=result, headers=a).json() == done.json()
    assert client.post(endpoint + '/result', json={**result, 'title': '覆盖'}, headers=a).status_code == 409
    history = client.get('/agent/v2/conversations/' + cid, headers=a).json()
    assert [e['role'] for e in history['events']] == ['user', 'assistant', 'tool']
    assert client.delete('/agent/v2/conversations/' + cid, headers=a).status_code == 200
    assert client.get('/agent/v2/conversations/' + cid, headers=a).status_code == 404
    assert client.get('/agent/v2/conversations', headers=a).json() == []


def test_server_result_requires_matching_real_job(client):
    user, headers = register(client)
    plan = ask(client, headers).json()
    action = plan['action']
    endpoint = '/agent/v2/actions/' + action['id']
    client.post(endpoint + '/claim', json={'mode': 'server'}, headers=headers)
    result = {'success': True, 'track_id': 987654, 'diagnostic': '下载完成'}
    assert client.post(endpoint + '/result', json=result, headers=headers).status_code == 400
    with session_scope() as session:
        track = Track(owner_scope=user['user_id'], platform='douyin', video_id=str(uuid4()), title='服务端视频', source_url=action['input_url'], audio_path='test')
        session.add(track); session.flush(); tid = track.id
        session.add(ExtractJob(raw_input=action['input_url'], user_id=user['user_id'], track_id=tid, status=JobStatus.DONE))
    assert client.post(endpoint + '/result', json={**result, 'track_id': tid}, headers=headers).json()['result']['source'] == 'server_verified'


def test_model_fallback_and_context_memory_evidence(client, monkeypatch):
    user, headers = register(client)
    client.post('/agent/memories', headers=headers, json={'user_id': user['user_id'], 'content': '优先手机提取'})
    monkeypatch.setattr(settings, 'ark_api_key', 'test')
    monkeypatch.setattr(settings, 'ark_models', 'unavailable,good')
    seen = []
    def post(url, **kwargs):
        model = kwargs['json']['model']; seen.append(model)
        if model == 'unavailable':
            return httpx.Response(503, request=httpx.Request('POST', url))
        payload = json.loads(kwargs['json']['messages'][1]['content'])
        assert '优先手机提取' in payload['active_memories']
        return httpx.Response(200, request=httpx.Request('POST', url), json={'choices': [{'message': {'content': json.dumps({
            'answer': '请确认提取方式', 'extract': True, 'track_ids': [], 'memory_proposal': '优先手机提取'})}}]})
    monkeypatch.setattr(extraction_agent.httpx, 'post', post)
    response = ask(client, headers).json()
    assert response['model'] == 'good'
    assert seen == ['unavailable', 'good']
    assert response['memory_proposal'] is None  # Ordinary conversation does not create memory.
    follow = ask(client, headers, text='记住，我优先手机提取', cid=response['conversation_id']).json()
    assert follow['memory_proposal'] == '优先手机提取'
    assert len(client.get('/agent/memories/' + user['user_id'], headers=headers).json()) == 1


def test_secrets_and_forged_tools_are_rejected(client):
    _, headers = register(client)
    assert ask(client, headers, text='API_KEY=secret-value').status_code == 400
    assert ask(client, headers, text='Cookie: sessionid=private').status_code == 400
    assert ask(client, headers, text='提取 https://douyin.com.evil.example/x').json()['action'] is None
    assert ask(client, headers, text='提取 https://someone:password@www.douyin.com/video/123').status_code == 400
    assert client.post('/agent/v2/chat', headers=headers, json={'request_id': str(uuid4()), 'message': '你好', 'user_id': 'forged'}).status_code == 422
    assert client.get('/agent/v2/conversations').status_code == 401


def test_auth_requests_are_rate_limited(client):
    for _ in range(20):
        assert client.post('/account/login', json={'phone': '13800138002', 'password': 'wrong-password'}).status_code == 401
    assert client.post('/account/login', json={'phone': '13800138002', 'password': 'wrong-password'}).status_code == 429


def test_invalid_credentials_do_not_echo_password(client):
    response = client.post('/account/register', json={'phone': 'bad', 'password': 'secret'})
    assert response.status_code == 422
    assert 'secret' not in response.text
    assert 'input' not in response.text


def test_current_memory_context_excludes_deleted_and_other_accounts(client, monkeypatch):
    alice, a = register(client)
    bob, b = register(client)
    mid = client.post('/agent/memories', headers=a, json={'user_id': alice['user_id'], 'content': '已删除的偏好'}).json()['id']
    client.delete('/agent/memories/' + alice['user_id'] + '/' + str(mid), headers=a)
    client.post('/agent/memories', headers=b, json={'user_id': bob['user_id'], 'content': '其他用户的偏好'})
    first = ask(client, a).json()
    aid = first['action']['id']
    client.post('/agent/v2/actions/' + aid + '/claim', headers=a, json={'mode': 'phone'})
    client.post('/agent/v2/actions/' + aid + '/result', headers=a, json={'success': False, 'diagnostic': 'HTTP 403'})
    monkeypatch.setattr(settings, 'ark_api_key', 'test')
    monkeypatch.setattr(settings, 'ark_models', 'good')
    def post(url, **kwargs):
        payload = json.loads(kwargs['json']['messages'][1]['content'])
        assert payload['active_memories'] == []
        assert any(e['role'] == 'tool' and e['source'] == 'device_reported' and e['diagnostic'] == 'HTTP 403'
                   for e in payload['recent_events'])
        return httpx.Response(200, request=httpx.Request('POST', url), json={'choices': [{'message': {'content': json.dumps({
            'answer': '手机报告HTTP 403，仅凭状态码还不能确定根因', 'extract': False, 'track_ids': [], 'memory_proposal': None})}}]})
    monkeypatch.setattr(extraction_agent.httpx, 'post', post)
    result = ask(client, a, text='为什么刚才失败了？', cid=first['conversation_id'])
    assert result.json()['action'] is None
    assert result.json()['model'] == 'good'
