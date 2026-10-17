"""Ark adapter. Keys and media payloads never appear in error messages or logs."""
from __future__ import annotations
import base64
import json
import math
from pathlib import Path
from urllib.parse import urlsplit
import httpx
from pydantic import BaseModel, Field, ConfigDict
from .config import settings
from .recreation_media import first_frame

PROMPTS = Path(__file__).parent/'prompts'

class ProviderError(RuntimeError): pass
class SubmissionUnknown(ProviderError): pass
class TaskMissing(ProviderError): pass

class VisualDescription(BaseModel):
    model_config = ConfigDict(extra='forbid')
    description: str = Field(min_length=10, max_length=4000)
    continuity: str = Field(max_length=600)
    uncertainties: list[str] = Field(default_factory=list, max_length=20)


def data_url(file: Path, mime: str) -> str:
    if file.stat().st_size > 40*1024*1024: raise ProviderError('分析素材超过大小限制')
    return 'data:'+mime+';base64,'+base64.b64encode(file.read_bytes()).decode()


def _request(method, path, *, submission=False, **kwargs):
    if not settings.ark_api_key: raise ProviderError('服务器尚未配置大模型凭据')
    try:
        with httpx.Client(timeout=settings.recreation_model_timeout, follow_redirects=False) as c:
            r=c.request(method, settings.ark_url.rstrip('/')+path,
                        headers={'Authorization': 'Bearer '+settings.ark_api_key}, **kwargs)
    except httpx.HTTPError as exc:
        if submission: raise SubmissionUnknown('提交响应未确认，平台可能已受理。请核对平台任务后再决定是否重新生成，避免重复计费。') from exc
        raise ProviderError('大模型网络暂时不可用，请稍后重试') from exc
    if r.status_code >= 500 and submission:
        raise SubmissionUnknown('平台未确认提交结果，可能已受理，请核对任务后再重试')
    if not r.is_success:
        try: code=str((r.json().get('error') or {}).get('code', ''))
        except ValueError: code=''
        if method=='GET' and '/contents/generations/tasks/' in path and r.status_code==404:
            raise TaskMissing('平台任务已过期或不可访问；未自动重新提交，请核对平台任务记录')
        if code=='ModelNotOpen': raise ProviderError('当前生成模型未开通，请检查服务器的模型配置')
        if r.status_code in (401,403): raise ProviderError('大模型凭据或模型访问权限不可用')
        if r.status_code in (402,429): raise ProviderError('大模型额度不足或请求限流，请检查平台状态后再重试')
        raise ProviderError(f'大模型请求未成功（HTTP {r.status_code}），请检查素材、提示词或模型配置')
    try: return r.json()
    except ValueError as exc:
        if submission: raise SubmissionUnknown('平台提交响应无法确认，请核对平台任务后再试') from exc
        raise ProviderError('大模型返回了无法读取的结果') from exc


def describe(file: Path, *, start: float, end: float, continuity: str) -> VisualDescription:
    system=(PROMPTS/'video-description-v1.txt').read_text()
    user=f'''请详细记录实际片段，原片区间 {start:.3f}–{end:.3f} 秒。输入为 2fps 抽帧，不能声称观察了每一帧。
输出 description（中文自然语言，完整分镜、主体外观、位置/遮挡、运动路径/速度/起止状态、机位运镜、光色、背景、可辨认文字；使用原片绝对秒数，并说明时间定位约有0.5秒误差）、continuity（稳定外观和片段结束状态）、uncertainties（不确定项目）。
前一片段连续性证据（仅作参考，不以此虚构本片段内容）：{continuity[:600]}
不能根据画面猜台词或音乐。不能添加原片未出现的细节。只输出 schema 对应 JSON。'''
    d=_request('POST','/chat/completions',json={
        'model':settings.recreation_analysis_model,'thinking':{'type':'disabled'},'max_tokens':4096,
        'messages':[{'role':'system','content':system},{'role':'user','content':[
            {'type':'video_url','video_url':{'url':data_url(file,'video/mp4'),'fps':2}}, {'type':'text','text':user}]}],
        'response_format':{'type':'json_schema','json_schema':{'name':'video_description','schema':VisualDescription.model_json_schema(),'strict':False}}})
    try:
        choice=d['choices'][0]
        if choice.get('finish_reason')=='length': raise ValueError('truncated')
        return VisualDescription.model_validate_json(choice['message']['content'])
    except (ValueError,KeyError,IndexError,TypeError) as exc: raise ProviderError('视频描述不完整或格式异常，请重新分析本任务') from exc


def describe_audio(file: Path, start: float) -> str:
    system=(PROMPTS/'audio-description-v1.txt').read_text().replace('{{source_start_sec}}',str(start)).replace('{{audio_facts_json}}','视频内有音轨，请实际读取。')
    d=_request('POST','/chat/completions',json={'model':settings.recreation_audio_model,'thinking':{'type':'disabled'},'max_tokens':1600,
        'messages':[{'role':'system','content':system},{'role':'user','content':[
            {'type':'video_url','video_url':{'url':data_url(file,'video/mp4'),'fps':.2}},
            {'type':'text','text':'请按原片绝对时间记录真实声音和能听清的原话，不能猜测；未能理解声音明确写未分析。'}]}]})
    try:
        text=d['choices'][0]['message']['content']
        if not isinstance(text,str) or not text.strip() or len(text)>8000: raise ValueError()
        return text
    except (KeyError,IndexError,TypeError,ValueError) as exc: raise ProviderError('声音描述未完成') from exc


def generation_prompt(description: str, continuity: str, guidance: str, start: float, end: float) -> str:
    text=f'''依据以下已确认的分镜文字重建画面。当前片段对应原片 {start:.3f}–{end:.3f} 秒，生成模型本地时间从0开始：将描述中的原片时间减去 {start:.3f} 秒执行，保持动作顺序和节奏。不要擅自增加情节、人物、道具、镜头或转场。
稳定外观与连续性：{continuity}
逐镜头详细描述：\n{description}\n用户补充要求：{guidance or '严格按分镜执行'}
优先保持主体数量/外观/服装、空间布局、起止姿势、动作路径、机位与光色一致，动作与运镜分开执行。不镜像反转、不改变道具归属、不随意变脸或变形。无法从文字精确约束的细节不保证相同。不要自行添加字幕、音乐或台词；声音由后期单独处理。'''
    if len(text)>6000: raise ValueError('本段提示词过长，请精简描述或补充要求')
    return text


def submit(file: Path, run, part) -> str:
    content=[{'type':'text','text':generation_prompt(part.description,part.continuity,run.guidance,part.start,part.end)}]
    if run.mode=='first_frame':
        image=first_frame(file,file.with_suffix('.jpg'))
        content.append({'type':'image_url','image_url':{'url':data_url(image,'image/jpeg')},'role':'first_frame'})
    d=_request('POST','/contents/generations/tasks',submission=True,json={'model':run.model,'content':content,
        'duration':max(2,min(12,math.ceil(part.end-part.start))),'resolution':run.resolution,
        'ratio':run.ratio,'watermark':True})
    task=d.get('id')
    if not isinstance(task,str) or not task or len(task)>128 or '/' in task:
        raise SubmissionUnknown('平台未返回可确认的任务 ID，请先核对平台任务，避免重复提交')
    return task


def poll(task_id: str) -> dict:
    return _request('GET','/contents/generations/tasks/'+task_id)


def _public_url(url: str) -> bool:
    # Returned URLs are provider data, never client input. Limit to Ark/TOS CDNs.
    host=(urlsplit(url).hostname or '').lower()
    return urlsplit(url).scheme=='https' and any(host==d or host.endswith('.'+d) for d in ['volces.com','volcengine.com','byteimg.com','ibytedtos.com'])


def download(url: str, dest: Path):
    if not _public_url(url): raise ProviderError('生成平台返回了不受信任的下载地址')
    temp=dest.with_suffix('.part.mp4'); dest.parent.mkdir(parents=True,exist_ok=True)
    try:
        # Revalidate redirects; never send the Ark API key to the result CDN.
        with httpx.Client(timeout=120,follow_redirects=False) as c:
            for _ in range(4):
                if not _public_url(url): raise ProviderError('生成结果的跳转地址无效')
                with c.stream('GET',url) as r:
                    if r.status_code in (301,302,303,307,308):
                        url=str(r.url.join(r.headers.get('location',''))); continue
                    if not r.is_success: raise ProviderError('生成结果下载失败，可重试下载而无需重新生成')
                    total=0
                    with temp.open('wb') as f:
                        for block in r.iter_bytes(65536):
                            total+=len(block)
                            if total>settings.recreation_max_bytes: raise ProviderError('生成结果超过大小限制')
                            f.write(block)
                    temp.replace(dest); return
            raise ProviderError('生成结果跳转次数过多')
    except httpx.HTTPError as exc: raise ProviderError('下载网络中断，工作进程会继续查询和下载') from exc
    finally: temp.unlink(missing_ok=True)
