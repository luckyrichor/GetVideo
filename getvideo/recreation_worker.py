"""Single persistent worker. Restart resumes polling, never repeats uncertain submits.
Run: .venv/bin/python -m getvideo.recreation_worker
"""
from __future__ import annotations
import argparse
import fcntl
import json
import logging
import time
from pathlib import Path
from sqlalchemy import select
from .db import SessionLocal, session_scope, init_db
from .recreation_models import RecreationProject as Project, RecreationClip as Clip, RecreationRun as Run, RecreationRunClip as RunClip
from . import recreation_media as media, recreation_provider as provider

log=logging.getLogger(__name__)


def update(model, key, **values):
    with session_scope() as s:
        row=s.get(model,key)
        for k,v in values.items():setattr(row,k,v)


def recover():
    with session_scope() as s:
        for c in s.scalars(select(Clip).where(Clip.status=='analyzing')):
            c.status='pending'  # Analysis has no paid generation task; prior committed clips survive.
        for part in s.scalars(select(RunClip).where(RunClip.status=='submitting')):
            if part.provider_task_id:part.status='running'
            else:
                part.status='submission_unknown';part.error='工作进程在提交期间重启，平台可能已受理；未自动重新提交，请核对平台记录'
                run=s.get(Run,part.run_id);run.status='submission_unknown';run.error=part.error


def part_file(p,c):
    return media.clip(media.safe_file(p.source_path), media.root()/p.id/'clips'/f'{c.position}.mp4', c.start,c.end)


def analyze_once():
    with session_scope() as s:
        p=s.scalar(select(Project).where(Project.status.in_(['queued','analyzing'])).order_by(Project.created_at).limit(1))
        if p is None:return False
        c=s.scalar(select(Clip).where(Clip.project_id==p.id,Clip.status=='pending').order_by(Clip.position).limit(1))
        if c is None:
            p.status='ready';p.revision=max(1,p.revision);return True
        c.status='analyzing';p.status='analyzing'
        prev=s.scalar(select(Clip).where(Clip.project_id==p.id,Clip.position<c.position).order_by(Clip.position.desc()).limit(1))
        continuity=prev.continuity if prev else ''
    try:
        file=part_file(p,c)
        visual=provider.describe(file,start=c.start,end=c.end,continuity=continuity)
        audio='未分析音轨'
        warnings=list(visual.uncertainties)
        if p.has_audio and p.analyze_audio:
            try:audio=provider.describe_audio(file,c.start)
            except provider.ProviderError:
                audio='音轨分析未成功；画面描述已保留，可重试分析或使用原音轨'
                warnings.append('音轨未完成分析，不能将画面推测当作台词或音乐')
        elif not p.has_audio:audio='源文件没有音轨'
        with session_scope() as s:
            cur=s.get(Clip,c.id)
            cur.description=visual.description;cur.original_description=visual.description;cur.continuity=visual.continuity
            cur.audio_description=audio;cur.uncertainties=json.dumps(warnings,ensure_ascii=False);cur.status='succeeded';cur.error=None
        # Next iteration marks the complete project ready, after all clip commits.
    except (ValueError,provider.ProviderError) as exc:
        update(Clip,c.id,status='failed',error=str(exc));update(Project,p.id,status='failed',error=str(exc))
    except Exception as exc:
        log.error('Analysis failed (%s)',type(exc).__name__)
        update(Clip,c.id,status='failed',error='视频分析异常，请重试');update(Project,p.id,status='failed',error='视频分析异常，请重试')
    return True


def generation_once():
    with SessionLocal() as s:
        r=s.scalar(select(Run).where(Run.status.in_(['queued','running','composing','cancel_requested'])).order_by(Run.created_at).limit(1))
        if r is None:return False
        p=s.get(Project,r.project_id)
        parts=list(s.scalars(select(RunClip).where(RunClip.run_id==r.id).order_by(RunClip.position)))
    active=next((x for x in parts if x.status=='running'),None)
    if r.status=='cancel_requested' and active is None:
        update(Run,r.id,status='canceled',error='已停止后续片段；已提交的任务可能已产生用量');return True
    part=active or next((x for x in parts if x.status=='pending'),None)
    try:
        if part is None:
            if any(x.status!='succeeded' for x in parts):
                update(Run,r.id,status='failed',error='部分片段未生成成功');return True
            update(Run,r.id,status='composing')
            first=media.facts(media.safe_file(parts[0].output_path))
            dest=media.root()/p.id/'runs'/r.id/'result.mp4'
            media.compose(media.safe_file(p.source_path),[(media.safe_file(x.output_path),x.end-x.start) for x in parts],dest,
                keep_audio=bool(r.keep_audio),width=first['width'],height=first['height'])
            update(Run,r.id,status='succeeded',result_path=str(dest.relative_to(media.root())),result_sha256=media.sha256(dest),result_bytes=dest.stat().st_size,error=None)
            return True
        if part.status=='pending':
            file=part_file(p,part)
            # Commit before making the paid POST. Any crash afterwards is uncertain, never re-POSTed.
            with session_scope() as s:
                current=s.get(Run,r.id)
                if current.status=='cancel_requested':return True
                current.status='running';s.get(RunClip,part.id).status='submitting'
            try:task=provider.submit(file,r,part)
            except provider.SubmissionUnknown as exc:
                update(RunClip,part.id,status='submission_unknown',error=str(exc));update(Run,r.id,status='submission_unknown',error=str(exc));return True
            update(RunClip,part.id,status='running',provider_task_id=task)
            return True
        try:result=provider.poll(part.provider_task_id)
        except provider.TaskMissing:
            raise
        except provider.ProviderError:
            # GET can be safely retried. Never turn a polling timeout into another paid POST.
            return False
        state=result.get('status')
        if state in ['queued','running','pending']:return False
        if state in ['failed','canceled','expired']:
            raise provider.ProviderError('生成平台未完成本段视频，请检查内容、额度或平台状态后重新生成')
        if state!='succeeded':return False
        path=media.root()/p.id/'runs'/r.id/f'{part.position}.mp4'
        if not path.exists():
            url=(result.get('content') or {}).get('video_url')
            if not isinstance(url,str):raise provider.ProviderError('平台未返回生成视频')
            try:provider.download(url,path)
            except provider.ProviderError:
                return False  # Existing task remains available; retry download, not generation.
        try:media.facts(path)
        except ValueError:
            path.unlink(missing_ok=True);raise
        update(RunClip,part.id,status='succeeded',output_path=str(path.relative_to(media.root())),usage_json=json.dumps(result.get('usage') or {}))
    except (ValueError,provider.ProviderError) as exc:
        if part:update(RunClip,part.id,status='failed',error=str(exc))
        update(Run,r.id,status='failed',error=str(exc))
    except Exception as exc:
        log.error('Generation failed (%s)',type(exc).__name__)
        # If submit was in flight, recovery will preserve the uncertain state.
        with SessionLocal() as s:
            current=s.get(RunClip,part.id) if part else None
            uncertain=current is not None and current.status=='submitting' and not current.provider_task_id
        if uncertain:
            update(RunClip,part.id,status='submission_unknown',error='提交结果未确认，请核对平台记录')
        update(Run,r.id,status='submission_unknown' if uncertain else 'failed',error='提交结果未确认，请核对平台记录，避免重复计费' if uncertain else '视频处理异常；已提交片段不会自动重复生成')
    return True


def run_once():
    return generation_once() or analyze_once()


def main():
    parser=argparse.ArgumentParser(description='GetVideo AI 持久任务工作进程')
    parser.add_argument('--once',action='store_true');args=parser.parse_args()
    init_db()
    with (media.root()/'worker.lock').open('a') as lock:
        try:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:raise SystemExit('已有 AI 视频工作进程运行')
        recover()
        while True:
            try:worked=run_once()
            except Exception as exc:
                log.error('Worker iteration failed (%s)',type(exc).__name__);worked=False
            if args.once:break
            time.sleep(1 if worked else 5)

if __name__=='__main__':main()
