"""Adapter contract: preserve prompt detail, no silent charging retries, no CDN credentials."""
from types import SimpleNamespace
import httpx
import pytest
from getvideo.config import settings
from getvideo import recreation_provider as p


def test_paid_timeout_is_unknown_not_retry(monkeypatch):
    monkeypatch.setattr(settings,'ark_api_key','sensitive-test-key')
    calls=[]
    def request(*args,**kwargs):
        calls.append(1)
        raise httpx.ReadTimeout('secret endpoint payload')
    monkeypatch.setattr(httpx.Client,'request',request)
    with pytest.raises(p.SubmissionUnknown) as error:p._request('POST','/contents/generations/tasks',submission=True,json={})
    assert len(calls)==1
    assert 'sensitive' not in str(error.value) and 'payload' not in str(error.value)


def test_first_frame_adapter_and_full_prompt(monkeypatch,tmp_path):
    image=tmp_path/'first.jpg';image.write_bytes(b'image')
    monkeypatch.setattr(p,'first_frame',lambda *a:image)
    payload={}
    def request(method,path,**kw):payload.update(kw['json']);return {'id':'task-safe-id'}
    monkeypatch.setattr(p,'_request',request)
    run=SimpleNamespace(model='fast',mode='first_frame',guidance='保留红色杯子',resolution='480p',ratio='16:9')
    part=SimpleNamespace(start=10,end=14.5,description='镜头从左向右移动，杯子位于画面左下方，随后人物抬起右手。',continuity='红杯子，白桌面')
    assert p.submit(tmp_path/'input.mp4',run,part)=='task-safe-id'
    assert payload['duration']==5
    assert part.description in payload['content'][0]['text'] and '10.000' in payload['content'][0]['text']
    assert payload['content'][1]['role']=='first_frame'
    assert payload['content'][1]['image_url']['url'].startswith('data:image/jpeg;base64,')
    assert payload['watermark'] is True

@pytest.mark.parametrize('url',['http://a.volces.com/x','https://volces.com.evil.test/x','https://127.0.0.1/x','file:///etc/passwd'])
def test_reject_untrusted_download_before_network(url,tmp_path,monkeypatch):
    def forbidden(*a,**k):raise AssertionError('network must not be attempted')
    monkeypatch.setattr(httpx.Client,'stream',forbidden)
    with pytest.raises(p.ProviderError):p.download(url,tmp_path/'out.mp4')


def test_result_cdn_never_receives_ark_key(monkeypatch,tmp_path):
    monkeypatch.setattr(settings,'ark_api_key','private-ark-key')
    original=httpx.Client
    requests=[]
    def response(request):
        requests.append(request)
        if len(requests)==1:return httpx.Response(302,headers={'location':'https://other.volces.com/result'})
        return httpx.Response(200,content=b'mp4-result')
    monkeypatch.setattr(httpx,'Client',lambda **kwargs:original(transport=httpx.MockTransport(response),**kwargs))
    dest=tmp_path/'video.mp4';p.download('https://example.volces.com/result',dest)
    assert dest.read_bytes()==b'mp4-result' and len(requests)==2
    assert all('authorization' not in r.headers and 'private-ark-key' not in str(r.url) for r in requests)
