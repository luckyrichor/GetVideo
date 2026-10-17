"""抖音原生视频提取：只采用作品完整视频流，绝不退回 BGM。"""
from __future__ import annotations

import logging
import subprocess
import tempfile
import time
from pathlib import Path
from urllib.parse import urlsplit, urlunsplit

import httpx

from ..config import settings
from ..sharelink import Platform, ResolvedLink
from .base import ExtractResult, ExtractorError, MediaInfo, NeedsLoginError
from .bilibili import _parse_cookie_file
from .probe import probe_duration
from .video import remux_video
from .ytdlp import _move_into_library

log = logging.getLogger(__name__)

_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)

API_DETAIL = "https://www.douyin.com/aweme/v1/web/aweme/detail/"

# Web 端固定参数。aid=6383 是抖音 Web 的应用 id。
_BASE_PARAMS = {
    "device_platform": "webapp",
    "aid": "6383",
    "channel": "channel_pc_web",
    "pc_client_type": "1",
    "version_code": "170400",
    "version_name": "17.4.0",
    "cookie_enabled": "true",
    "platform": "PC",
    "downlink": "10",
}

# filter_reason → 给用户看的人话
_FILTER_MSG = {
    "core_dep": "视频不存在或已被删除",
    "self_see": "作品仅作者自己可见",
    "friend": "作品仅好友可见",
    "private": "作品已设为私密",
    "reviewing": "作品审核中",
}


class DouyinExtractor:
    platform = Platform.DOUYIN

    def extract(self, link: ResolvedLink, *, cookie_file: Path | None = None) -> ExtractResult:
        settings.ensure_dirs()
        cookies = _parse_cookie_file(cookie_file)
        if "ttwid" not in cookies:
            # 说得具体些：这是最常见的失败原因
            log.warning("抖音 cookie 里没有 ttwid，detail 接口大概率会 403")

        headers = {
            "User-Agent": _UA,
            "Referer": f"https://www.douyin.com/video/{link.video_id}",
            "Accept": "application/json, text/plain, */*",
            "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
        }

        with httpx.Client(timeout=settings.http_timeout, headers=headers, cookies=cookies, follow_redirects=True) as client:
            detail = self._fetch_detail(client, link)
            info = self._to_media_info(detail, link)
            source, urls = self._video_sources(detail)

            with tempfile.TemporaryDirectory(dir=settings.tmp_dir) as td:
                raw_path = Path(td) / f"{info.video_id}.bin"
                self._download(client, urls, raw_path)
                # 保留画面和声音，校验后以 MP4 流拷贝封装。
                out_path = remux_video(raw_path, Path(td) / info.video_id)
                if probed := probe_duration(out_path):
                    info.duration_sec = probed
                info.tags = [*info.tags, f"video_source:{source}"]
                final = _move_into_library(out_path, info)

        log.info("抖音视频来源: %s", source)
        return ExtractResult(
            info=info,
            audio_path=final,
            audio_format=final.suffix.lstrip("."),
            audio_bytes=final.stat().st_size,
        )

    # ---------- 各步骤 ----------

    def _fetch_detail(self, client: httpx.Client, link: ResolvedLink) -> dict:
        params = {**_BASE_PARAMS, "aweme_id": link.video_id}
        try:
            resp = client.get(API_DETAIL, params=params)
        except httpx.HTTPError as exc:
            raise ExtractorError(f"detail 接口请求失败：{exc}") from exc

        if resp.status_code == 403:
            raise NeedsLoginError("抖音拒绝了请求（403），访客凭证可能已失效，请重试或扫码登录")
        if "json" not in resp.headers.get("content-type", ""):
            raise ExtractorError("detail 接口返回了非 JSON（大概率被风控拦截）")

        data = resp.json()
        aweme = data.get("aweme_detail")
        if not aweme:
            reason = (data.get("filter_detail") or {}).get("filter_reason", "")
            msg = _FILTER_MSG.get(reason, f"接口没有返回作品内容（filter_reason={reason or '未知'}）")
            if reason in ("self_see", "friend", "private"):
                raise NeedsLoginError(msg + "，需要有权限的账号登录后才能提取")
            raise ExtractorError(msg)
        return aweme

    def _video_sources(self, aweme: dict) -> tuple[str, list[str]]:
        urls = _video_urls(aweme)
        if not urls:
            raise ExtractorError("没有可下载的视频地址；音乐地址不能替代视频")
        return "video", urls

    def _download(self, client: httpx.Client, urls: list[str], dest: Path) -> None:
        last: Exception | None = None
        for url in urls:
            try:
                with client.stream("GET", url, headers={"Referer": "https://www.douyin.com/"}) as resp:
                    resp.raise_for_status()
                    with dest.open("wb") as f:
                        for chunk in resp.iter_bytes(1 << 16):
                            f.write(chunk)
                if dest.stat().st_size > 1024:
                    return
                last = ExtractorError(f"下载内容过小（{dest.stat().st_size} 字节）")
            except Exception as exc:
                last = exc
                log.warning("地址下载失败，换下一个: %s", exc)
        raise ExtractorError(f"所有候选地址都下载失败：{last}")

    def _to_media_info(self, aweme: dict, link: ResolvedLink) -> MediaInfo:
        author = aweme.get("author") or {}
        music = aweme.get("music") or {}
        video = aweme.get("video") or {}
        create_time = aweme.get("create_time")

        # 抖音没有独立标题，desc 就是文案；太长就截断当标题用
        desc = (aweme.get("desc") or "").strip()
        music_title = (music.get("title") or "").strip()
        title = desc[:60] or music_title or f"douyin-{link.video_id}"

        tags = [t["hashtag_name"] for t in (aweme.get("text_extra") or []) if t.get("hashtag_name")]
        if music_title and music_title != title:
            tags.append(music_title)

        duration_ms = aweme.get("duration") or video.get("duration")
        return MediaInfo(
            platform=Platform.DOUYIN,
            video_id=str(aweme.get("aweme_id") or link.video_id),
            title=title,
            webpage_url=f"https://www.douyin.com/video/{aweme.get('aweme_id') or link.video_id}",
            uploader=author.get("nickname") or (music.get("author") or None),
            description=desc,
            thumbnail_url=_first(_url_list((video.get("cover") or {}))),
            upload_date=time.strftime("%Y%m%d", time.localtime(create_time)) if create_time else None,
            duration_sec=float(duration_ms) / 1000 if duration_ms else None,
            tags=tags,
        )


def _as_sec(value, *, unit: str) -> float | None:
    """时长归一到秒。抖音同一份响应里 music 用秒、aweme 用毫秒。"""
    try:
        v = float(value)
    except (TypeError, ValueError):
        return None
    if v <= 0:
        return None
    return v / 1000 if unit == "ms" else v


def _url_list(node) -> list[str]:
    if not isinstance(node, dict):
        return []
    return [u for u in (node.get("url_list") or []) if isinstance(u, str) and u.startswith("http")]


def _first(items: list[str]) -> str | None:
    return items[0] if items else None


def _video_urls(aweme: dict) -> list[str]:
    """视频地址候选，无水印优先。"""
    video = aweme.get("video") or {}
    urls: list[str] = []
    # play_addr 系列里 h264/bit_rate 通常是无水印源
    for key in ("play_addr_h264", "play_addr", "play_addr_265"):
        urls.extend(_url_list(video.get(key)))
    for br in video.get("bit_rate") or []:
        urls.extend(_url_list(br.get("play_addr")))
    urls = [_clean_playback_url(url) for url in urls]
    urls.extend(_url_list(video.get("download_addr")))
    # 去重保序
    seen: set[str] = set()
    return [u for u in urls if not (u in seen or seen.add(u))]


def _clean_playback_url(url: str) -> str:
    parts = urlsplit(url)
    host = parts.hostname or ""
    if parts.path == "/aweme/v1/playwm/" and any(
        host == domain or host.endswith("." + domain)
        for domain in ("snssdk.com", "douyin.com", "iesdouyin.com", "amemv.com")
    ):
        return urlunsplit(parts._replace(path="/aweme/v1/play/"))
    return url
