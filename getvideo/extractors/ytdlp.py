"""基于 yt-dlp 的提取器，B 站和抖音共用。

选 yt-dlp 而不是自己拼接口：两个平台的播放地址签名它都在跟，
自己手写等于把维护成本全接过来。平台差异收在 _platform_opts 里。
"""
from __future__ import annotations

import logging
import re
import shutil
import tempfile
from pathlib import Path

import yt_dlp

from ..config import settings
from ..sharelink import Platform, ResolvedLink
from .base import ExtractResult, ExtractorError, MediaInfo, NeedsLoginError

log = logging.getLogger(__name__)

_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)

# 平台报错文本 → 我们的异常类型。用于把「没登录」和「真的挂了」分开，
# 前者要提示用户扫码，后者要报警。
_LOGIN_HINTS = (
    "登录", "login", "sign in", "cookies", "会员", "vip",
    "account", "private", "权限",
)


def _looks_like_login_problem(msg: str) -> bool:
    low = msg.lower()
    return any(h in msg or h in low for h in _LOGIN_HINTS)


class YtDlpExtractor:
    """通用实现；platform 只用于路由和日志。"""

    def __init__(self, platform: Platform) -> None:
        self.platform = platform

    # ---------- 选项 ----------

    def _platform_opts(self) -> dict:
        if self.platform is Platform.BILIBILI:
            return {
                # B 站 DASH 画面和声音分别下载，合并为完整视频
                "format": "bestvideo[vcodec^=avc1]+bestaudio[acodec^=mp4a]/best[ext=mp4]/bestvideo+bestaudio/best",
                "http_headers": {"User-Agent": _UA, "Referer": "https://www.bilibili.com/"},
                # 分 P 视频只要用户指定的那一 P，不要整个合集
                "noplaylist": True,
            }
        if self.platform is Platform.DOUYIN:
            return {
                # 抖音下载完整合成视频流
                "format": "bestvideo+bestaudio/best",
                "http_headers": {"User-Agent": _UA, "Referer": "https://www.douyin.com/"},
                "noplaylist": True,
            }
        return {"format": "bestvideo+bestaudio/best"}

    def _ydl_opts(self, outdir: Path, cookie_file: Path | None) -> dict:
        opts: dict = {
            "quiet": True,
            "no_warnings": True,
            "noprogress": True,
            "outtmpl": str(outdir / "%(id)s.%(ext)s"),
            "restrictfilenames": True,
            "socket_timeout": int(settings.http_timeout),
            "retries": 3,
            "fragment_retries": 3,
            "merge_output_format": "mp4",
            # 元数据写进文件标签，脱离数据库也还认得出是什么
            "writethumbnail": False,
            "logger": _QuietLogger(),
        }
        opts.update(self._platform_opts())
        if cookie_file and cookie_file.exists():
            opts["cookiefile"] = str(cookie_file)
        return opts

    # ---------- 主流程 ----------

    def extract(self, link: ResolvedLink, *, cookie_file: Path | None = None) -> ExtractResult:
        settings.ensure_dirs()
        # 下到临时目录，成功后才搬进 audio_dir —— 避免半个文件污染视频库
        with tempfile.TemporaryDirectory(dir=settings.tmp_dir) as td:
            tmpdir = Path(td)
            opts = self._ydl_opts(tmpdir, cookie_file)
            try:
                with yt_dlp.YoutubeDL(opts) as ydl:
                    raw = ydl.extract_info(link.url, download=True)
            except yt_dlp.utils.DownloadError as exc:
                msg = _clean_ytdlp_error(str(exc))
                if _looks_like_login_problem(msg):
                    raise NeedsLoginError(f"{self.platform.value} 需要登录后才能提取：{msg}") from exc
                raise ExtractorError(f"{self.platform.value} 提取失败：{msg}") from exc
            except Exception as exc:  # yt-dlp 内部异常种类很杂，统一收口
                raise ExtractorError(f"{self.platform.value} 提取异常：{exc}") from exc

            if raw is None:
                raise ExtractorError("下载器没有返回任何信息")
            # 少数情况仍会返回 playlist（合集页），取第一条
            if raw.get("_type") == "playlist" and raw.get("entries"):
                raw = raw["entries"][0]

            produced = _pick_video_file(tmpdir)
            if produced is None:
                raise ExtractorError(f"下载完成但没找到完整视频文件（目录内容: {[p.name for p in tmpdir.iterdir()]}）")

            info = self._to_media_info(raw, link)
            from .video import remux_video
            produced = remux_video(produced, tmpdir / "complete-video")
            final = _move_into_library(produced, info)
            return ExtractResult(
                info=info,
                audio_path=final,
                audio_format=final.suffix.lstrip("."),
                audio_bytes=final.stat().st_size,
            )

    def _to_media_info(self, raw: dict, link: ResolvedLink) -> MediaInfo:
        return MediaInfo(
            platform=self.platform,
            video_id=str(raw.get("id") or link.video_id),
            title=(raw.get("title") or "").strip() or f"{self.platform.value}-{link.video_id}",
            webpage_url=raw.get("webpage_url") or link.url,
            uploader=raw.get("uploader") or raw.get("channel") or raw.get("creator"),
            description=raw.get("description"),
            thumbnail_url=raw.get("thumbnail"),
            upload_date=raw.get("upload_date"),
            duration_sec=_as_float(raw.get("duration")),
            tags=[t for t in (raw.get("tags") or []) if isinstance(t, str)],
        )


class _QuietLogger:
    """把 yt-dlp 的输出接到 logging，不要直接打到 stdout。"""

    def debug(self, msg: str) -> None:
        log.debug("yt-dlp: %s", msg)

    def info(self, msg: str) -> None:
        log.debug("yt-dlp: %s", msg)

    def warning(self, msg: str) -> None:
        log.warning("yt-dlp: %s", msg)

    def error(self, msg: str) -> None:
        log.error("yt-dlp: %s", msg)


_ANSI_RE = re.compile(r"\x1b\[[0-9;]*m")


def _clean_ytdlp_error(msg: str) -> str:
    msg = _ANSI_RE.sub("", msg)
    return msg.replace("ERROR: ", "").strip()


def _as_float(v) -> float | None:
    try:
        return float(v) if v is not None else None
    except (TypeError, ValueError):
        return None


_VIDEO_EXTS = {".mp4", ".mkv", ".webm", ".mov"}


def _pick_video_file(d: Path) -> Path | None:
    """挑出后处理产物。

    优先目标格式；ffmpeg 后处理若被跳过（源已是目标编码）就退而取
    任意视频扩展名里最大的那个。
    """
    files = [p for p in d.iterdir() if p.is_file()]
    target = [p for p in files if p.suffix.lower() == f".{settings.audio_format}"]
    if target:
        return max(target, key=lambda p: p.stat().st_size)
    audio = [p for p in files if p.suffix.lower() in _VIDEO_EXTS]
    if audio:
        return max(audio, key=lambda p: p.stat().st_size)
    return None


_UNSAFE = re.compile(r'[<>:"/\\|?*\x00-\x1f]')


def safe_name(text: str, limit: int = 80) -> str:
    """把标题变成安全文件名。保留中文，只砍掉文件系统敏感字符。"""
    cleaned = _UNSAFE.sub("_", text).strip().strip(".")
    cleaned = re.sub(r"\s+", " ", cleaned)
    return (cleaned[:limit] or "untitled").rstrip()


def _move_into_library(src: Path, info: MediaInfo) -> Path:
    """搬进 audio_dir/<platform>/<video_id>-<标题>.<ext>

    文件名带 video_id 打头：重名不会撞，肉眼也能反查回源视频。
    """
    from ..storage_scope import output_root
    dest_dir = output_root(settings.audio_dir) / info.platform.value
    dest_dir.mkdir(parents=True, exist_ok=True)
    dest = dest_dir / f"{info.video_id}-{safe_name(info.title)}{src.suffix.lower()}"
    shutil.move(str(src), str(dest))
    return dest
