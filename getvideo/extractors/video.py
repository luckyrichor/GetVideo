"""Keep the complete picture and soundtrack; stream-copy into MP4."""
from __future__ import annotations
import json
import subprocess
from pathlib import Path
from .base import ExtractorError


def probe_video(path: Path) -> dict:
    try:
        proc = subprocess.run(["ffprobe", "-v", "error", "-show_streams", "-show_format",
                               "-of", "json", str(path)], capture_output=True, text=True, timeout=30, check=True)
        info = json.loads(proc.stdout)
    except (OSError, ValueError, subprocess.SubprocessError) as exc:
        raise ExtractorError("无法读取视频文件") from exc
    if not any(s.get("codec_type") == "video" and s.get("width", 0) > 0 and s.get("height", 0) > 0
               for s in info.get("streams", [])):
        raise ExtractorError("下载内容没有视频画面，不能作为视频保存")
    return info


def remux_video(source: Path, dest_stem: Path, audio_source: Path | None = None) -> Path:
    probe_video(source)
    dest = dest_stem.with_name(dest_stem.name + ".mp4")
    cmd = ["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y", "-i", str(source)]
    if audio_source is not None:
        cmd += ["-i", str(audio_source)]
    cmd += ["-map", "0:v:0", "-map", "1:a:0" if audio_source is not None else "0:a:0?",
            "-c", "copy", "-movflags", "+faststart", str(dest)]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=1800)
        if proc.returncode or not dest.exists() or not dest.stat().st_size:
            raise ExtractorError(f"视频封装失败：{proc.stderr.strip()[:500]}")
        probe_video(dest)
    except (OSError, subprocess.TimeoutExpired) as exc:
        dest.unlink(missing_ok=True)
        raise ExtractorError("视频封装失败或超时，请重试") from exc
    except ExtractorError:
        dest.unlink(missing_ok=True)
        raise
    return dest
