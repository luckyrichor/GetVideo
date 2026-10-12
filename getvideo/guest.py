"""访客 cookie 预热。

B 站和抖音都对「一个 cookie 都不带」的请求直接上风控 —— B 站回
HTTP 412，抖音回空数据。浏览器里首次打开首页时会被下发一批设备指纹
cookie（B 站的 buvid3、抖音的 ttwid），带上它们就能正常拿到播放地址。

所以在真正下载前先访问一次首页，把这批 cookie 存成 Netscape 格式给
yt-dlp 用。这不是登录 —— 拿不到会员音质，但公开内容够了；用户扫码
登录（P1）后会用他自己的凭证覆盖这份。
"""
from __future__ import annotations

import logging
import random
import string
import time
from pathlib import Path

import httpx

from .config import settings
from .sharelink import Platform

log = logging.getLogger(__name__)

_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)

# 设备指纹 cookie 有效期很长，但我们自己按半天刷一次，避免被判定为长期同一指纹
_TTL_SEC = 6 * 3600

_BOOTSTRAP = {
    Platform.BILIBILI: {
        "domain": ".bilibili.com",
        "urls": [
            "https://www.bilibili.com/",
            # spi 接口专门下发 buvid3/buvid4，首页偶尔漏发
            "https://api.bilibili.com/x/frontend/finger/spi",
        ],
        "referer": "https://www.bilibili.com/",
    },
    Platform.DOUYIN: {
        "domain": ".douyin.com",
        "urls": [
            "https://www.douyin.com/",
        ],
        "referer": "https://www.douyin.com/",
    },
}

# 抖音的设备 id 不从首页下发（首页只回一个 2KB 的 JS 壳），要向字节的
# ttwid 注册接口单独申请。没有 ttwid，detail 接口一律 403。
_TTWID_REGISTER = "https://ttwid.bytedance.com/ttwid/union/register/"
_TTWID_BODY = {
    "region": "cn",
    "aid": 1768,
    "needFid": False,
    "service": "www.ixigua.com",
    "migrate_info": {"ticket": "", "source": "node"},
    "cbUrlProtocol": "https",
    "union": True,
}


def _random_ms_token(length: int = 107) -> str:
    """msToken 客户端本地生成即可，服务端只校验长度和字符集。"""
    alphabet = string.ascii_letters + string.digits + "-_"
    return "".join(random.choices(alphabet, k=length))


def _douyin_extra_cookies(client: httpx.Client) -> dict[str, str]:
    extra: dict[str, str] = {"msToken": _random_ms_token()}
    try:
        resp = client.post(_TTWID_REGISTER, json=_TTWID_BODY, timeout=settings.http_timeout)
        ttwid = resp.cookies.get("ttwid")
        if ttwid:
            extra["ttwid"] = ttwid
        else:
            log.warning("ttwid 注册接口没有下发 cookie（status=%s）", resp.status_code)
    except httpx.HTTPError as exc:
        log.warning("ttwid 注册失败: %s", exc)
    return extra


def _cache_path(platform: Platform) -> Path:
    return settings.cookie_dir / f"_guest__{platform.value}.txt"


def _is_fresh(path: Path) -> bool:
    return path.exists() and path.stat().st_size > 0 and (time.time() - path.stat().st_mtime) < _TTL_SEC


def _write_netscape(path: Path, domain: str, cookies: dict[str, str]) -> None:
    expiry = int(time.time()) + 365 * 24 * 3600
    lines = ["# Netscape HTTP Cookie File", "# 由 GetVideo 自动生成的访客 cookie，可随时删除", ""]
    for name, value in cookies.items():
        # domain / include_subdomains / path / secure / expiry / name / value
        lines.append(f"{domain}\tTRUE\t/\tFALSE\t{expiry}\t{name}\t{value}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    path.chmod(0o600)


def ensure_guest_cookies(platform: Platform, *, force: bool = False) -> Path | None:
    """返回可用的访客 cookie 文件；拿不到返回 None（调用方应照常尝试下载）。"""
    conf = _BOOTSTRAP.get(platform)
    if conf is None:
        return None

    path = _cache_path(platform)
    if not force and _is_fresh(path):
        return path

    settings.ensure_dirs()
    headers = {
        "User-Agent": _UA,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
        "Referer": conf["referer"],
    }
    try:
        with httpx.Client(timeout=settings.http_timeout, follow_redirects=True, headers=headers) as client:
            for url in conf["urls"]:
                try:
                    client.get(url)
                except httpx.HTTPError as exc:
                    log.debug("预热请求失败 %s: %s", url, exc)
            cookies = dict(client.cookies)
            if platform is Platform.DOUYIN:
                cookies.update(_douyin_extra_cookies(client))
    except Exception as exc:
        log.warning("%s 访客 cookie 预热失败: %s", platform.value, exc)
        return path if path.exists() else None

    if not cookies:
        log.warning("%s 预热没拿到任何 cookie", platform.value)
        return path if path.exists() else None

    _write_netscape(path, conf["domain"], cookies)
    log.info("%s 访客 cookie 已就绪: %s", platform.value, ", ".join(sorted(cookies)))
    return path
