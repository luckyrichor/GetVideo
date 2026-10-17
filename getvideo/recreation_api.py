"""Authenticated API for persistent video→description→video projects."""
from __future__ import annotations
import json
import shutil
from pathlib import Path
from uuid import uuid4
from typing import Literal
from fastapi import APIRouter, Depends, HTTPException, Query, Request
from fastapi.responses import FileResponse
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import select
from .accounts import current_user
from .config import settings
from .db import session_scope, SessionLocal, next_updated_seq
from .models import AppUser, Track
from .recreation_models import RecreationProject as Project, RecreationClip as Clip, RecreationRun as Run, RecreationRunClip as RunClip
from . import recreation_media as media
from .recreation_provider import generation_prompt

router=APIRouter(prefix='/v2/recreations',tags=['video recreation'])
ACTIVE={'queued','running','submitting','cancel_requested','composing'}

class KeyBody(BaseModel):
    model_config=ConfigDict(extra='forbid')
    request_key: str=Field(pattern=r'^[A-Za-z0-9_-]{8,80}$')
    analyze_audio: bool=True

class AnalyzeBody(BaseModel):
    model_config=ConfigDict(extra='forbid')
    retry_failed: bool=False

class ClipEdit(BaseModel):
    model_config=ConfigDict(extra='forbid')
    id: str
    description: str=Field(min_length=10,max_length=4000)

class DescriptionEdit(BaseModel):
    model_config=ConfigDict(extra='forbid')
    revision: int=Field(ge=0)
    guidance: str=Field(default='',max_length=600)
    clips: list[ClipEdit]=Field(min_length=1,max_length=150)

class GenerateBody(BaseModel):
    model_config=ConfigDict(extra='forbid')
    request_key: str=Field(pattern=r'^[A-Za-z0-9_-]{8,80}$')
    revision: int=Field(ge=1)
    mode: Literal['text','first_frame']='text'
    resolution: Literal['480p','720p','1080p']='480p'
    keep_audio: bool=True
    preview: bool=False
    acknowledge_duplicate_risk: bool=False


def ai_user(user: AppUser=Depends(current_user)):
    allowed={x.strip() for x in settings.recreation_allowed_users.split(',') if x.strip()}
    if allowed and user.id not in allowed: raise HTTPException(403,'此账号尚未启用服务器 AI 视频功能')
    return user


def owned(s, pid, user):
    p=s.get(Project,pid)
    if p is None or p.owner_id!=user.id: raise HTTPException(404,'AI 视频任务不存在')
    return p


def clips(s,pid):return list(s.scalars(select(Clip).where(Clip.project_id==pid).order_by(Clip.position)))

def project_out(s,p):
    runs=list(s.scalars(select(Run).where(Run.project_id==p.id).order_by(Run.created_at.desc())))
    return dict(id=p.id,title=p.title,status=p.status,error=p.error,revision=p.revision,guidance=p.guidance,
        source_sha256=p.source_sha256,duration=p.duration,width=p.width,height=p.height,has_audio=bool(p.has_audio),
        analysis_model=settings.recreation_analysis_model,generation_model=settings.recreation_generation_model,
        source_url=f'/v2/recreations/{p.id}/source',clips=[dict(id=c.id,position=c.position,start=c.start,end=c.end,
        status=c.status,description=c.description,original_description=c.original_description,audio_description=c.audio_description,uncertainties=json.loads(c.uncertainties),error=c.error) for c in clips(s,p.id)],
        runs=[run_out(s,r) for r in runs])


def run_out(s,r):
    parts=list(s.scalars(select(RunClip).where(RunClip.run_id==r.id).order_by(RunClip.position)))
    return dict(id=r.id,status=r.status,error=r.error,revision=r.revision,mode=r.mode,resolution=r.resolution,
        keep_audio=bool(r.keep_audio),model=r.model,result_sha256=r.result_sha256,result_bytes=r.result_bytes,total=len(parts),completed=sum(p.status=='succeeded' for p in parts),
        duration=sum(p.end-p.start for p in parts),result_track_id=r.result_track_id,
        download_url=f'/v2/recreations/{r.project_id}/runs/{r.id}/video' if r.status=='succeeded' else None,
        usage_tokens=sum((json.loads(p.usage_json or '{}').get('total_tokens') or 0) for p in parts))


def require_key():
    if not settings.ark_api_key: raise HTTPException(503,'服务器尚未配置大模型凭据')


def find_key(s,uid,key):return s.scalar(select(Project).where(Project.owner_id==uid,Project.request_key==key))

def register_file(uid,key,title,source,info,analyze_audio):
    pid=source.parent.name
    with session_scope() as s:
        old=find_key(s,uid,key)
        if old:return project_out(s,old)
        pending=s.scalars(select(Project).where(Project.owner_id==uid,Project.status.in_(['uploaded','queued','analyzing']))).all()
        if len(pending)>=5: raise HTTPException(429,'待处理素材过多，请先完成已有任务')
        p=Project(id=pid,owner_id=uid,request_key=key,title=title,source_path=str(source.relative_to(media.root())),
            source_sha256=media.sha256(source),duration=info['duration'],width=info['width'],height=info['height'],
            has_audio=int(info['has_audio']),analyze_audio=int(analyze_audio),status='uploaded')
        s.add(p)
        for i,(start,end) in enumerate(media.split_ranges(p.duration)):
            s.add(Clip(id=uuid4().hex,project_id=pid,position=i,start=start,end=end))
        s.flush();return project_out(s,p)

@router.get('/capabilities')
def capabilities(user: AppUser=Depends(ai_user)):
    return dict(configured=bool(settings.ark_api_key),analysis_model=settings.recreation_analysis_model,
        generation_model=settings.recreation_generation_model,max_bytes=settings.recreation_max_bytes,
        max_seconds=settings.recreation_max_seconds,clip_seconds=settings.recreation_clip_seconds,
        modes=['text','first_frame'],resolutions=['480p','720p','1080p'])

@router.get('')
def projects(user: AppUser=Depends(ai_user)):
    with SessionLocal() as s:
        rows=s.scalars(select(Project).where(Project.owner_id==user.id).order_by(Project.created_at.desc()).limit(30)).all()
        return [project_out(s,p) for p in rows]

@router.post('/upload')
async def upload(request: Request, request_key: str=Query(pattern=r'^[A-Za-z0-9_-]{8,80}$'),
                 title: str=Query(default='视频',min_length=1,max_length=120), analyze_audio: bool=True, user: AppUser=Depends(ai_user)):
    with SessionLocal() as s:
        old=find_key(s,user.id,request_key)
        if old:return project_out(s,old)
    length=request.headers.get('content-length')
    if length and (not length.isdigit() or int(length)>settings.recreation_max_bytes): raise HTTPException(413,'视频超过上传上限')
    directory=media.root()/uuid4().hex;directory.mkdir()
    temp=directory/'upload.part';source=directory/'source.mp4'
    try:
        total=0
        with temp.open('wb') as f:
            async for block in request.stream():
                total+=len(block)
                if total>settings.recreation_max_bytes: raise HTTPException(413,'视频超过上传上限，请先剪辑')
                f.write(block)
        if not total: raise HTTPException(400,'视频内容为空')
        # Validate off the event loop; files/keys never enter model logs.
        from starlette.concurrency import run_in_threadpool
        info=await run_in_threadpool(media.facts,temp)
        temp.replace(source)
        result=await run_in_threadpool(register_file,user.id,request_key,title,source,info,analyze_audio)
        if result['id']!=directory.name:shutil.rmtree(directory)
        return result
    except ValueError as exc:
        shutil.rmtree(directory,ignore_errors=True); raise HTTPException(400,str(exc)) from exc
    except BaseException:
        shutil.rmtree(directory,ignore_errors=True);raise

@router.post('/from-track/{track_id}')
def from_track(track_id: int, body: KeyBody, user: AppUser=Depends(ai_user)):
    with SessionLocal() as s:
        old=find_key(s,user.id,body.request_key)
        if old:return project_out(s,old)
        t=s.get(Track,track_id)
        if t is None or t.owner_scope!=user.id or t.deleted:raise HTTPException(404,'云端视频不存在')
        path=(settings.audio_dir/t.audio_path).resolve()
        if not path.is_relative_to(settings.audio_dir.resolve()) or not path.is_file():raise HTTPException(410,'云端视频文件已丢失')
        if path.stat().st_size>settings.recreation_max_bytes:raise HTTPException(413,'视频过大，请先剪辑')
        directory=media.root()/uuid4().hex;directory.mkdir();source=directory/'source.mp4'
        try:
            shutil.copyfile(path,source); info=media.facts(source)
            result=register_file(user.id,body.request_key,t.title[:120] or '视频',source,info,body.analyze_audio)
            if result['id']!=directory.name:shutil.rmtree(directory)
            return result
        except ValueError as exc:
            shutil.rmtree(directory,ignore_errors=True);raise HTTPException(400,str(exc)) from exc
        except BaseException:
            shutil.rmtree(directory,ignore_errors=True);raise

@router.get('/{pid}')
def get_project(pid: str,user: AppUser=Depends(ai_user)):
    with SessionLocal() as s:return project_out(s,owned(s,pid,user))

@router.get('/{pid}/source')
def source_video(pid: str,user: AppUser=Depends(ai_user)):
    with SessionLocal() as s:
        p=owned(s,pid,user);path=media.safe_file(p.source_path)
        if not path.is_file():raise HTTPException(410,'素材已丢失')
        return FileResponse(path,media_type='video/mp4',filename='source.mp4')

@router.post('/{pid}/analyze')
def analyze(pid: str,body: AnalyzeBody,user: AppUser=Depends(ai_user)):
    require_key()
    with session_scope() as s:
        p=owned(s,pid,user)
        if p.status in ['queued','analyzing','ready']:return project_out(s,p)
        if p.status=='failed' and not body.retry_failed:raise HTTPException(409,'重新分析可能产生调用费用，请明确选择重试')
        for c in clips(s,p.id):
            if c.status!='succeeded':c.status='pending';c.error=None
        p.status='queued';p.error=None;s.flush();return project_out(s,p)

@router.put('/{pid}/description')
def edit_description(pid: str,body: DescriptionEdit,user: AppUser=Depends(ai_user)):
    with session_scope() as s:
        p=owned(s,pid,user)
        if p.status!='ready' or p.revision!=body.revision:raise HTTPException(409,'描述版本已变化或分析尚未完成，请刷新')
        rows=clips(s,p.id)
        incoming={x.id:x.description for x in body.clips}
        if len(incoming)!=len(body.clips) or set(incoming)!={c.id for c in rows}:raise HTTPException(400,'须完整提交当前任务的分段描述')
        for c in rows:
            try:generation_prompt(incoming[c.id],c.continuity,body.guidance,c.start,c.end)
            except ValueError as exc:raise HTTPException(400,str(exc)) from exc
            c.description=incoming[c.id]
        p.guidance=body.guidance;p.revision+=1;s.flush();return project_out(s,p)

@router.post('/{pid}/generate')
def generate(pid: str,body: GenerateBody,user: AppUser=Depends(ai_user)):
    require_key()
    with session_scope() as s:
        p=owned(s,pid,user)
        existing=s.scalar(select(Run).where(Run.project_id==pid,Run.request_key==body.request_key))
        if existing:return project_out(s,p)
        if p.status!='ready' or p.revision!=body.revision:raise HTTPException(409,'请先完成分析，并保存当前描述版本')
        runs=s.scalars(select(Run).where(Run.project_id==pid)).all()
        if any(r.status in ACTIVE for r in runs):raise HTTPException(409,'已有生成任务进行中，请勿重复提交')
        if any(r.status=='submission_unknown' for r in runs) and not body.acknowledge_duplicate_risk:
            raise HTTPException(409,'存在提交结果不确定的任务。请核对平台记录，并明确确认可能重复计费后再试')
        # One active run per account bounds accidental repeated spending across projects.
        if s.scalar(select(Run).where(Run.owner_id==user.id,Run.status.in_(ACTIVE)).limit(1)):
            raise HTTPException(409,'此账号已有其他生成任务，请等待完成')
        rid=uuid4().hex
        r=Run(id=rid,project_id=pid,owner_id=user.id,request_key=body.request_key,revision=p.revision,
            model=settings.recreation_generation_model,resolution=body.resolution,mode=body.mode,keep_audio=int(body.keep_audio and p.has_audio),
            ratio=media.nearest_ratio(p.width,p.height),guidance=p.guidance,status='queued')
        s.add(r)
        selected=clips(s,pid);selected=selected[:1] if body.preview else selected
        for c in selected:
            generation_prompt(c.description,c.continuity,p.guidance,c.start,c.end)
            s.add(RunClip(id=uuid4().hex,run_id=rid,position=c.position,start=c.start,end=c.end,
                description=c.description,continuity=c.continuity,status='pending'))
        s.flush();return project_out(s,p)

@router.post('/{pid}/runs/{rid}/cancel')
def cancel(pid: str,rid: str,user: AppUser=Depends(ai_user)):
    with session_scope() as s:
        p=owned(s,pid,user);r=s.get(Run,rid)
        if r is None or r.project_id!=pid:raise HTTPException(404,'生成任务不存在')
        if r.status in ACTIVE:r.status='cancel_requested'
        s.flush();return project_out(s,p)

@router.get('/{pid}/runs/{rid}/video')
def result_video(pid: str,rid: str,user: AppUser=Depends(ai_user)):
    with SessionLocal() as s:
        owned(s,pid,user);r=s.get(Run,rid)
        if r is None or r.project_id!=pid or r.status!='succeeded' or not r.result_path:raise HTTPException(404,'生成视频尚未完成')
        path=media.safe_file(r.result_path)
        if not path.is_file():raise HTTPException(410,'生成文件已丢失')
        return FileResponse(path,media_type='video/mp4',filename='AI-video.mp4')

@router.post('/{pid}/runs/{rid}/save')
def save(pid: str,rid: str,user: AppUser=Depends(ai_user)):
    from .account_api import track_out
    with session_scope() as s:
        p=owned(s,pid,user);r=s.get(Run,rid)
        if r is None or r.project_id!=pid or r.status!='succeeded' or not r.result_path:raise HTTPException(409,'生成视频尚未完成')
        if r.result_track_id:
            t=s.get(Track,r.result_track_id)
            if t is not None:
                if t.deleted:
                    source=media.safe_file(r.result_path)
                    dest=settings.audio_dir/t.audio_path;dest.parent.mkdir(parents=True,exist_ok=True)
                    shutil.copyfile(source,dest);t.deleted=0;t.updated_seq=next_updated_seq(s);s.flush()
                return track_out(t)
        source=media.safe_file(r.result_path); info=media.facts(source)
        directory=settings.audio_dir/'generated'/user.id;directory.mkdir(parents=True,exist_ok=True)
        dest=directory/(rid+'.mp4');shutil.copyfile(source,dest)
        t=Track(owner_scope=user.id,platform='generated',video_id=rid,source_url='getvideo:recreation:'+pid,
            title=p.title+'（AI生成）',uploader='GetVideo AI',description='由用户确认的文字生成；原素材保留',
            duration_sec=info['duration'],audio_path=str(dest.relative_to(settings.audio_dir)),audio_format='mp4',
            audio_bytes=dest.stat().st_size,audio_sha256=media.sha256(dest),updated_seq=next_updated_seq(s))
        s.add(t);s.flush();r.result_track_id=t.id
        return track_out(t)
