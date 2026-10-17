"""Bounded media preparation and concatenation. Original files remain intact."""
from __future__ import annotations
import hashlib
import json
import math
import subprocess
from pathlib import Path
from .config import settings


def root() -> Path:
    settings.recreation_dir.mkdir(parents=True, exist_ok=True)
    return settings.recreation_dir


def safe_file(relative: str) -> Path:
    p = (root() / relative).resolve()
    if not p.is_relative_to(root().resolve()):
        raise ValueError('无效素材路径')
    return p


def command(args: list, timeout=300):
    try:
        p = subprocess.run([str(a) for a in args], capture_output=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise ValueError('视频处理失败或超时') from exc
    if p.returncode:
        raise ValueError('视频格式无法处理，请使用有效的 MP4 视频')
    return p.stdout


def facts(file: Path) -> dict:
    d = json.loads(command(['ffprobe', '-v', 'error', '-protocol_whitelist', 'file,pipe', '-f', 'mov', '-show_streams', '-show_format', '-of', 'json', file], 30))
    v = next((s for s in d.get('streams', []) if s.get('codec_type') == 'video' and not s.get('disposition', {}).get('attached_pic')), None)
    if not v:
        raise ValueError('文件中没有视频画面')
    duration = float(d.get('format', {}).get('duration', 0))
    if not math.isfinite(duration) or duration <= 0 or duration > settings.recreation_max_seconds:
        raise ValueError(f'视频时长须在 0 到 {settings.recreation_max_seconds:g} 秒之间，请先剪辑')
    w, h = int(v.get('width', 0)), int(v.get('height', 0))
    if w <= 0 or h <= 0 or w*h > 3840*2160 or max(w,h)>8192:
        raise ValueError('视频尺寸无效或过大，请先导出不超过 4K 的版本')
    rotations = [v.get('tags', {}).get('rotate', 0), *[x.get('rotation', 0) for x in v.get('side_data_list', [])]]
    if any(abs(float(x)) % 180 == 90 for x in rotations): w, h = h, w
    return dict(duration=duration, width=w, height=h, has_audio=any(s.get('codec_type') == 'audio' for s in d.get('streams', [])))


def split_ranges(duration: float, size: int | None = None) -> list[tuple[float, float]]:
    size = size or settings.recreation_clip_seconds
    if not 2 <= size <= 12: raise ValueError('生成分段必须为 2–12 秒')
    # Distribute the short tail, preserving total length and avoiding tiny last clips.
    count = max(1, math.ceil(duration / size))
    frames=max(1,round(duration*24))
    points=[round(i*frames/count)/24 for i in range(count+1)]
    points[-1]=duration
    return [(round(points[i],6),round(points[i+1],6)) for i in range(count)]


def sha256(file: Path) -> str:
    h = hashlib.sha256()
    with file.open('rb') as f:
        for block in iter(lambda: f.read(1 << 20), b''): h.update(block)
    return h.hexdigest()


def clip(source: Path, dest: Path, start: float, end: float) -> Path:
    if dest.exists(): return dest
    dest.parent.mkdir(parents=True, exist_ok=True)
    temp = dest.with_name(dest.stem+'.part.mp4')
    try:
        command(['ffmpeg', '-nostdin', '-v', 'error', '-y', '-ss', start, '-i', source,
                 '-t', end-start, '-map', '0:v:0', '-map', '0:a:0?', '-vf',
                 "scale=720:720:force_original_aspect_ratio=decrease:force_divisible_by=2,setsar=1",
                 '-r', '24', '-c:v', 'libx264', '-preset', 'fast', '-crf', '24', '-pix_fmt', 'yuv420p',
                 '-c:a', 'aac', '-b:a', '96k', '-movflags', '+faststart', temp])
        facts(temp)
        if temp.stat().st_size > 40*1024*1024: raise ValueError('分析片段过大，请缩短视频或分段')
        temp.replace(dest)
        return dest
    finally: temp.unlink(missing_ok=True)


def first_frame(source: Path, dest: Path) -> Path:
    command(['ffmpeg', '-nostdin', '-v', 'error', '-y', '-i', source, '-frames:v', '1', dest], 30)
    return dest


def nearest_ratio(w: int, h: int) -> str:
    ratios = ['16:9', '9:16', '1:1', '4:3', '3:4', '21:9']
    return min(ratios, key=lambda r: abs(math.log(w/h/(int(r.split(':')[0])/int(r.split(':')[1])))))


def compose(source: Path, pieces: list[tuple[Path, float]], dest: Path, *, keep_audio: bool, width: int, height: int):
    dest.parent.mkdir(parents=True, exist_ok=True)
    normalized=[]
    try:
        for i, (file, duration) in enumerate(pieces):
            f = facts(file)
            if f['duration'] + .15 < duration: raise ValueError('生成视频比预期片段短，请重试该任务')
            norm = dest.parent / f'normalized-{i}.mp4'; normalized.append(norm)
            command(['ffmpeg', '-nostdin', '-v', 'error', '-y', '-i', file, '-t', duration,
                '-an', '-frames:v', max(1,round(duration*24)), '-vf', f'scale={width}:{height}:force_original_aspect_ratio=decrease,pad={width}:{height}:(ow-iw)/2:(oh-ih)/2,setsar=1',
                '-r', '24', '-c:v', 'libx264', '-preset', 'fast', '-crf', '20', '-pix_fmt', 'yuv420p', norm])
        listing=dest.parent/'concat.txt'; listing.write_text(''.join(f"file '{p.name}'\n" for p in normalized))
        silent=dest.parent/'composed-silent.mp4'
        command(['ffmpeg','-nostdin','-v','error','-y','-f','concat','-safe','1','-i',listing,'-c','copy',silent])
        temp=dest.with_name('result.part.mp4')
        if keep_audio:
            command(['ffmpeg','-nostdin','-v','error','-y','-i',silent,'-i',source,'-map','0:v:0','-map','1:a:0?',
                     '-c:v','copy','-c:a','aac','-b:a','128k','-t',sum(x[1] for x in pieces),'-movflags','+faststart',temp])
        else: silent.replace(temp)
        check=facts(temp)
        if abs(check['duration']-sum(x[1] for x in pieces)) > .25: raise ValueError('合成视频时长校验失败')
        temp.replace(dest)
    finally:
        for p in normalized: p.unlink(missing_ok=True)
        for name in ['concat.txt','composed-silent.mp4','result.part.mp4']: (dest.parent/name).unlink(missing_ok=True)
