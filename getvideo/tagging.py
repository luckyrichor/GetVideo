"""把元数据写进视频文件本身。

数据库可能丢、可能重建，但文件跟着用户走到手机上 —— 标签写全了，
播放器里才不是一串 video_id。
"""
from __future__ import annotations

import logging
from pathlib import Path

from mutagen import File as MutagenFile
from mutagen.mp4 import MP4

from .extractors.base import MediaInfo

log = logging.getLogger(__name__)


def write_tags(path: Path, info: MediaInfo) -> bool:
    """尽力写标签。失败只记日志，不能因为标签写不上就让整条链路失败。"""
    try:
        audio = MutagenFile(str(path))
        if audio is None:
            log.warning("mutagen 不认识这个格式，跳过打标: %s", path.name)
            return False

        if isinstance(audio, MP4):
            audio["\xa9nam"] = [info.title]
            if info.uploader:
                audio["\xa9ART"] = [info.uploader]
                audio["aART"] = [info.uploader]
            audio["\xa9alb"] = [f"GetVideo-{info.platform.value}"]
            if info.upload_date and len(info.upload_date) >= 4:
                audio["\xa9day"] = [info.upload_date[:4]]
            # 留个回溯锚点：拿到文件也能找回源视频
            audio["\xa9cmt"] = [info.webpage_url]
            if info.tags:
                audio["\xa9gen"] = [", ".join(info.tags[:5])]
        else:
            # MP3 默认返回原始 ID3，必须启用 easy 才能使用 title 等键。
            audio = MutagenFile(str(path), easy=True)
            if audio is None:
                return False
            if audio.tags is None:
                audio.add_tags()
            for key, value in (
                ("title", info.title),
                ("artist", info.uploader),
                ("album", f"GetVideo-{info.platform.value}"),
                ("date", info.upload_date[:4] if info.upload_date else None),
                ("genre", ", ".join(info.tags[:5]) if info.tags else None),
            ):
                if value:
                    try:
                        audio[key] = [value]
                    except (KeyError, ValueError):  # 某些格式不支持该键
                        log.warning("格式不支持标签 %s: %s", key, path.name)

        audio.save()
        return True
    except Exception as exc:
        log.warning("写标签失败 %s: %s", path.name, exc)
        return False
