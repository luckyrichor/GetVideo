"""Real media + API ownership, immutable submissions, restart and no duplicate spend."""
from pathlib import Path
from uuid import uuid4
import json
import subprocess
import pytest
from fastapi.testclient import TestClient
from getvideo.api import app
from getvideo.config import settings
from getvideo.db import session_scope,SessionLocal
from getvideo.recreation_models import RecreationProject as Project,RecreationRun as Run,RecreationRunClip as RunClip
from getvideo.recreation_provider import VisualDescription,SubmissionUnknown
from getvideo import recreation_provider as provider,recreation_worker as worker,recreation_media as media
from test_account_api import register

@pytest.fixture(scope='module')
def mp4(tmp_path_factory):
    p=tmp_path_factory.mktemp('ai-video')/'video.mp4'
    subprocess.run(['ffmpeg','-nostdin','-v','error','-y','-f','lavfi','-i','testsrc2=size=160x90:rate=24:duration=2',
        '-f','lavfi','-i','sine=frequency=440:duration=2','-c:v','libx264','-c:a','aac','-shortest',str(p)],check=True)
    return p

@pytest.fixture
def setup(monkeypatch,mp4):
    monkeypatch.setattr(settings,'ark_api_key','test-key-never-send')
    monkeypatch.setattr(settings,'recreation_clip_seconds',10)
    monkeypatch.setattr(provider,'describe',lambda *a,**kw:VisualDescription(description='0–2秒，固定机位，彩色背景的形状连续移动，无文字。',continuity='保持主体颜色及固定机位',uncertainties=['细小形状需要人工核对']))
    monkeypatch.setattr(provider,'describe_audio',lambda *a:'连续测试音，没有可辨认台词')
    calls=[]
    monkeypatch.setattr(provider,'submit',lambda *a:(calls.append('POST') or 'test-task-'+uuid4().hex))
    monkeypatch.setattr(provider,'poll',lambda *a:{'status':'succeeded','content':{'video_url':'https://example.invalid/result'},'usage':{'total_tokens':10}})
    monkeypatch.setattr(provider,'download',lambda url,dest:(dest.parent.mkdir(parents=True,exist_ok=True),dest.write_bytes(mp4.read_bytes())))
    with TestClient(app) as c:
        uid,headers=register(c)
        yield c,uid,headers,calls
    with session_scope() as s:
        # Keep the persistent worker's fixture queues isolated from the next test.
        for p in s.query(Project).filter(Project.owner_id==uid):p.status='ready'
        for r in s.query(Run).filter(Run.owner_id==uid):
            if r.status in ['queued','running','composing','cancel_requested']:r.status='canceled'


def upload(c,h,mp4,key=None):
    r=c.post('/v2/recreations/upload',headers={**h,'content-type':'video/mp4'},params={'request_key':key or uuid4().hex,'title':'测试视频'},content=mp4.read_bytes())
    assert r.status_code==200,r.text
    return r.json()


def analyzed(c,h,mp4):
    p=upload(c,h,mp4)
    r=c.post('/v2/recreations/'+p['id']+'/analyze',headers=h,json={})
    assert r.status_code==200
    assert worker.analyze_once();assert worker.analyze_once()
    p=c.get('/v2/recreations/'+p['id'],headers=h).json()
    assert p['status']=='ready' and p['revision']==1
    return p


def generate(c,h,p,**extra):
    body={'request_key':uuid4().hex,'revision':p['revision'],**extra}
    r=c.post('/v2/recreations/'+p['id']+'/generate',headers=h,json=body)
    assert r.status_code==200,r.text
    return r.json(),body


def test_unauthenticated_and_cross_account_files(setup,mp4):
    c,uid,h,_=setup
    assert c.get('/v2/recreations').status_code==401
    p=upload(c,h,mp4);_,b=register(c)
    assert c.get('/v2/recreations',headers=b).json()==[]
    for suffix in ['', '/source']:
        assert c.get('/v2/recreations/'+p['id']+suffix,headers=b).status_code==404
    assert c.post('/v2/recreations/'+p['id']+'/analyze',headers=b,json={}).status_code==404
    assert c.get('/v2/recreations/'+p['id']+'/source',headers=h).content==mp4.read_bytes()


def test_upload_rejects_invalid_oversized_and_idempotence(setup,mp4,monkeypatch):
    c,uid,h,_=setup;key=uuid4().hex
    p=upload(c,h,mp4,key)
    assert upload(c,h,mp4,key)['id']==p['id']
    r=c.post('/v2/recreations/upload',headers=h,params={'request_key':uuid4().hex},content=b'not video')
    assert r.status_code==400
    monkeypatch.setattr(settings,'recreation_max_bytes',4)
    assert c.post('/v2/recreations/upload',headers=h,params={'request_key':uuid4().hex},content=b'12345').status_code==413


def test_full_round_trip_edit_snapshot_generate_save_range(setup,mp4):
    c,uid,h,calls=setup;p=analyzed(c,h,mp4);url='/v2/recreations/'+p['id']
    edit={'revision':1,'guidance':'保持构图，不增加物体','clips':[{'id':x['id'],'description':x['description']+' 用户核对：保持固定镜头。'} for x in p['clips']]}
    p=c.put(url+'/description',headers=h,json=edit).json()
    assert p['revision']==2
    assert c.put(url+'/description',headers=h,json=edit).status_code==409
    p,body=generate(c,h,p)
    run=p['runs'][0];rid=run['id']
    c.post(url+'/generate',headers=h,json=body)
    assert worker.generation_once()  # One paid submit.
    worker.recover()               # Restart resumes the known remote task.
    assert worker.generation_once()  # Poll/download, not another POST.
    assert worker.generation_once()  # Real FFmpeg compose with original audio.
    assert calls==['POST']
    p=c.get(url,headers=h).json();run=p['runs'][0]
    assert run['status']=='succeeded' and run['usage_tokens']==10 and run['result_sha256']
    data=c.get(run['download_url'],headers=h)
    assert data.status_code==200 and data.headers['content-type']=='video/mp4'
    assert c.get(run['download_url'],headers={**h,'Range':'bytes=0-3'}).status_code==206
    a=c.post(url+'/runs/'+rid+'/save',headers=h);assert a.status_code==200
    b=c.post(url+'/runs/'+rid+'/save',headers=h);assert a.json()['id']==b.json()['id']
    assert any(x['id']==a.json()['id'] for x in c.get('/v2/sync',headers=h).json()['items'])
    _,other=register(c)
    assert c.get(run['download_url'],headers=other).status_code==404
    assert c.post(url+'/runs/'+rid+'/save',headers=other).status_code==404
    assert c.get('/v2/video/'+str(a.json()['id']),headers=other).status_code==404
    with SessionLocal() as s:
        snapshot=s.query(RunClip).filter_by(run_id=rid).first()
        assert '用户核对' in snapshot.description


def test_unknown_submit_is_not_retried_and_requires_ack(setup,mp4,monkeypatch):
    c,uid,h,calls=setup;p=analyzed(c,h,mp4)
    def fail(*a):calls.append('POST');raise SubmissionUnknown('平台可能已受理，请核对')
    monkeypatch.setattr(provider,'submit',fail)
    p,body=generate(c,h,p);worker.generation_once();worker.recover();worker.generation_once()
    p=c.get('/v2/recreations/'+p['id'],headers=h).json()
    assert p['runs'][0]['status']=='submission_unknown' and calls==['POST']
    r=c.post('/v2/recreations/'+p['id']+'/generate',headers=h,json={'revision':1,'request_key':uuid4().hex})
    assert r.status_code==409


def test_crash_between_post_and_save_is_unknown(setup,mp4):
    c,uid,h,_=setup;p=analyzed(c,h,mp4);p,_=generate(c,h,p)
    rid=p['runs'][0]['id']
    with session_scope() as s:
        s.get(Run,rid).status='running';s.query(RunClip).filter_by(run_id=rid).first().status='submitting'
    worker.recover()
    assert c.get('/v2/recreations/'+p['id'],headers=h).json()['runs'][0]['status']=='submission_unknown'


def test_cancel_before_submit_spends_nothing(setup,mp4):
    c,uid,h,calls=setup;p=analyzed(c,h,mp4);p,_=generate(c,h,p);rid=p['runs'][0]['id']
    c.post('/v2/recreations/'+p['id']+'/runs/'+rid+'/cancel',headers=h)
    worker.generation_once()
    assert calls==[]
    assert c.get('/v2/recreations/'+p['id'],headers=h).json()['runs'][0]['status']=='canceled'


def test_key_missing_and_allowlist(setup,monkeypatch):
    c,uid,h,_=setup
    monkeypatch.setattr(settings,'ark_api_key','')
    assert c.get('/v2/recreations/capabilities',headers=h).json()['configured'] is False
    monkeypatch.setattr(settings,'recreation_allowed_users','someone_else')
    assert c.get('/v2/recreations',headers=h).status_code==403

@pytest.mark.parametrize('duration',[.5,2,10,10.01,21,177.42,299])
def test_segment_plan_preserves_total_and_limits(duration):
    ranges=media.split_ranges(duration)
    assert ranges[0][0]==0 and ranges[-1][1]==duration
    assert all(0<b-a<=12 for a,b in ranges)
    assert all(abs(ranges[i][1]-ranges[i+1][0])<1e-5 for i in range(len(ranges)-1))
    assert sum(b-a for a,b in ranges)==pytest.approx(duration)


def test_multiple_fractional_clips_compose_without_frame_drift(mp4,tmp_path):
    dest=tmp_path/'result.mp4'
    pieces=[(mp4,1.125),(mp4,1.375)]
    media.compose(mp4,pieces,dest,keep_audio=False,width=160,height=90)
    result=media.facts(dest)
    assert abs(result['duration']-2.5)<.05
    assert result['has_audio'] is False
