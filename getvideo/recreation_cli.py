"""Authenticated client: GETVIDEO_SESSION is a GetVideo session, never a model key."""
import argparse
import json
import os
from pathlib import Path
from uuid import uuid4
from urllib.parse import urlsplit
import httpx


def main():
    p=argparse.ArgumentParser(description='GetVideo 视频描述与重建客户端')
    p.add_argument('--server',default='http://127.0.0.1:8900')
    commands=p.add_subparsers(dest='command',required=True)
    upload=commands.add_parser('upload');upload.add_argument('file',type=Path);upload.add_argument('--no-audio',action='store_true');upload.add_argument('--request-key',default=None)
    commands.add_parser('list')
    for name in ['status','analyze','generate','download']:
        sub=commands.add_parser(name);sub.add_argument('project')
        if name=='generate':
            sub.add_argument('--revision',type=int,required=True);sub.add_argument('--request-key',required=True)
            sub.add_argument('--mode',choices=['text','first_frame'],default='text')
            sub.add_argument('--resolution',choices=['480p','720p','1080p'],default='480p')
            sub.add_argument('--full',action='store_true');sub.add_argument('--silent',action='store_true')
        if name=='download':sub.add_argument('run');sub.add_argument('file',type=Path)
    a=p.parse_args();token=os.environ.get('GETVIDEO_SESSION')
    if not token:p.error('请通过 GETVIDEO_SESSION 设置已登录的 GetVideo 会话；不要使用模型 API Key')
    url=urlsplit(a.server)
    if not (url.scheme=='https' or (url.scheme=='http' and url.hostname in ['localhost','127.0.0.1'])):p.error('远程连接必须使用 HTTPS')
    with httpx.Client(base_url=a.server.rstrip('/'),headers={'X-GetVideo-Session':token},timeout=180,follow_redirects=False) as c:
        root='/v2/recreations'
        if a.command=='list':r=c.get(root)
        elif a.command=='upload':
            with a.file.open('rb') as f:r=c.post(root+'/upload',params={'request_key':a.request_key or uuid4().hex,'title':a.file.stem[:120],'analyze_audio':not a.no_audio},content=f,headers={'Content-Type':'video/mp4'})
        elif a.command=='status':r=c.get(root+'/'+a.project)
        elif a.command=='analyze':r=c.post(root+'/'+a.project+'/analyze',json={'retry_failed':True})
        elif a.command=='generate':r=c.post(root+'/'+a.project+'/generate',json={'request_key':a.request_key,'revision':a.revision,'mode':a.mode,'resolution':a.resolution,'preview':not a.full,'keep_audio':not a.silent})
        else:
            with c.stream('GET',root+'/'+a.project+'/runs/'+a.run+'/video') as response:
                if not response.is_success:raise SystemExit(f'下载失败：HTTP {response.status_code}')
                temp=a.file.with_suffix('.part.mp4')
                try:
                    with temp.open('wb') as f:
                        for block in response.iter_bytes():f.write(block)
                    temp.replace(a.file)
                finally:temp.unlink(missing_ok=True)
            print(str(a.file));return
        if not r.is_success:raise SystemExit(f'操作失败：HTTP {r.status_code}；{r.text[:500]}')
        print(json.dumps(r.json(),ensure_ascii=False,indent=2))

if __name__=='__main__':main()
