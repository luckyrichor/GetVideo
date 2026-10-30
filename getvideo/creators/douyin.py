"""抖音博主主页适配器。

抖音 Web 端的 ``aweme/v1/web/aweme/post/`` 从 2026 年起被
ArgusSecurityPlugin 门禁：直连会返回 ``403 Uifid Not Found`` 或
``Signature Not Found``。这里保留两条路：

1. 直连：带上用户导入的 Cookie 试一次。部分出口 IP / 账号仍能成功，
   成功时最快，也不需要浏览器。
2. 浏览器桥接：用 Playwright 打开主页，捕获页面自己发出的、已经由
   抖音 SDK 签好名的 ``aweme/post`` 响应。需要额外安装
   ``playwright`` 与 Chromium，并导入抖音登录 Cookie。

两条路都拿不到时给出可执行的提示，不伪装成「博主没有视频」。
"""
from __future__ import annotations

import logging
import re
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.parse import quote

import httpx

from ..config import settings
from ..extractors.bilibili import _parse_cookie_file
from ..sharelink import CreatorRef, Platform
from .base import (
    CreatorAuthError,
    CreatorError,
    CreatorNotFound,
    CreatorProfile,
    CreatorRangeError,
    CreatorUnavailable,
    CreatorVideo,
    CreatorWindow,
    VideoStats,
)

log = logging.getLogger(__name__)

API_POST = "https://www.douyin.com/aweme/v1/web/aweme/post/"
API_USER_INFO = "https://www.iesdouyin.com/web/api/v2/user/info/"
_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"
)

_BASE_PARAMS = {
    "device_platform": "webapp",
    "aid": "6383",
    "channel": "channel_pc_web",
    "update_version_code": "170400",
    "pc_client_type": "1",
    "pc_libra_divert": "Windows",
    "version_code": "290100",
    "version_name": "29.1.0",
    "cookie_enabled": "true",
    "screen_width": "1536",
    "screen_height": "864",
    "browser_language": "zh-CN",
    "browser_platform": "Win32",
    "browser_name": "Chrome",
    "browser_version": "139.0.0.0",
    "browser_online": "true",
    "engine_name": "Blink",
    "engine_version": "139.0.0.0",
    "os_name": "Windows",
    "os_version": "10",
    "cpu_core_num": "16",
    "device_memory": "8",
    "platform": "PC",
    "downlink": "10",
    "effective_type": "4g",
    "round_trip_time": "200",
    "support_h265": "1",
    "support_dash": "1",
    "uifid": "",
}

_BROWSER_INSTALL_HINT = (
    "请先导入抖音登录 Cookie，并在服务端安装浏览器桥接："
    "pip install playwright && python -m playwright install chromium；"
    "必要时把 GETVIDEO_DOUYIN_BROWSER_HEADLESS 设为 false 完成一次人工验证。"
)


def _iso(ts: int | None) -> str | None:
    if not ts:
        return None
    try:
        return datetime.fromtimestamp(int(ts), timezone.utc).isoformat()
    except (OSError, OverflowError, ValueError):
        return None


def _int(value) -> int | None:
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _first_url(node: Any) -> str | None:
    if not isinstance(node, dict):
        return None
    for url in node.get("url_list") or []:
        if isinstance(url, str) and url.startswith("http"):
            return url
    return None


_SEC_UID_RE = re.compile(r"^MS4wLjAB[A-Za-z0-9_-]{8,}$")


def _iter_users(value: Any):
    """从搜索接口/页面状态里递归揪出用户对象，不依赖固定的嵌套结构。"""
    if isinstance(value, dict):
        sec = value.get("sec_uid") or value.get("sec_user_id")
        if isinstance(sec, str) and _SEC_UID_RE.match(sec):
            if value.get("unique_id") or value.get("short_id") or value.get("nickname"):
                yield value
        for child in value.values():
            yield from _iter_users(child)
    elif isinstance(value, list):
        for child in value:
            yield from _iter_users(child)


def _has_video(aweme: dict) -> bool:
    """图集/图文作品没有视频流，批量下载时跳过，避免每条都失败。"""
    video = aweme.get("video") if isinstance(aweme.get("video"), dict) else {}
    if any(_first_url(video.get(key)) for key in ("play_addr", "play_addr_h264", "play_addr_265", "download_addr")):
        return True
    for item in video.get("bit_rate") or []:
        if isinstance(item, dict) and _first_url(item.get("play_addr")):
            return True
    return False


def _cover(video: dict) -> str | None:
    return (
        _first_url(video.get("cover"))
        or _first_url(video.get("origin_cover"))
        or _first_url(video.get("dynamic_cover"))
    )


class DouyinBrowserBridge:
    """用 Playwright 捕获页面自己签名的作品列表响应。"""

    def resolve_user(self, query: str, *, cookie_file: Path | None) -> dict:
        """用抖音搜索页把抖音号/昵称解析成用户对象。

        搜索接口同样被 Argus 门禁，所以不在 Python 里拼签名，而是让
        浏览器页面自己发搜索请求，再监听响应。需要用户已导入登录 Cookie。
        """
        try:
            from playwright.sync_api import sync_playwright
        except ImportError as exc:  # pragma: no cover
            raise CreatorUnavailable("未安装 Playwright：" + _BROWSER_INSTALL_HINT) from exc

        cookies = _parse_cookie_file(cookie_file)
        if not cookies.get("sessionid"):
            raise CreatorAuthError("抖音按抖音号/昵称搜索需要登录 Cookie；请先在 App 导入抖音登录凭证")

        query = (query or "").strip()
        if not query:
            raise CreatorNotFound("没有提供抖音号或昵称")

        candidates: dict[str, dict] = {}

        def remember(user: dict) -> None:
            sec = user.get("sec_uid") or user.get("sec_user_id")
            if isinstance(sec, str) and _SEC_UID_RE.match(sec):
                candidates.setdefault(sec, user)

        def on_response(response) -> None:
            url = response.url or ""
            if "/aweme/v1/web/" not in url:
                return
            if not any(key in url for key in ("search", "discover", "user")):
                return
            try:
                data = response.json()
            except Exception:
                return
            for user in _iter_users(data):
                remember(user)

        timeout_ms = int(settings.douyin_browser_timeout * 1000)
        with sync_playwright() as playwright:
            launch_options = {
                "headless": bool(settings.douyin_browser_headless),
                "args": ["--disable-blink-features=AutomationControlled", "--disable-dev-shm-usage", "--no-sandbox"],
            }
            if settings.douyin_browser_channel:
                launch_options["channel"] = settings.douyin_browser_channel
            browser = playwright.chromium.launch(**launch_options)
            context = browser.new_context(
                user_agent=_UA,
                locale="zh-CN",
                viewport={"width": 1536, "height": 864},
            )
            try:
                context.add_cookies(
                    [
                        {
                            "name": name,
                            "value": value,
                            "domain": ".douyin.com",
                            "path": "/",
                            "secure": True,
                            "sameSite": "Lax",
                        }
                        for name, value in cookies.items()
                        if name and value
                    ]
                )
                page = context.new_page()
                page.on("response", on_response)
                search_url = f"https://www.douyin.com/search/{quote(query, safe='')}?type=user"
                try:
                    page.goto(search_url, wait_until="domcontentloaded", timeout=timeout_ms)
                except Exception as exc:
                    log.warning("抖音搜索页打开超时/失败: %s", exc)
                page.wait_for_timeout(3500)

                blocked = _blocked_reason(page)
                if blocked:
                    if settings.douyin_browser_headless:
                        raise CreatorUnavailable(
                            f"抖音搜索要求安全验证（{blocked}），无头浏览器无法完成。"
                            "请在有头模式完成验证，或重新导入抖音 Cookie。"
                        )
                    deadline = time.time() + 120
                    while time.time() < deadline and _blocked_reason(page):
                        page.wait_for_timeout(2000)
                    if _blocked_reason(page):
                        raise CreatorUnavailable("抖音安全验证未完成，请重试")

                for _ in range(3):
                    try:
                        page.evaluate("window.scrollTo(0, document.body.scrollHeight)")
                    except Exception:
                        pass
                    page.wait_for_timeout(1000)

                # 有些搜索页只把结果渲染进 DOM，这里补一层链接兜底。
                try:
                    links = page.eval_on_selector_all(
                        'a[href*="/user/MS4wLjAB"]',
                        "els => els.slice(0, 20).map(e => ({href: e.href, text: (e.innerText || '').slice(0, 100)}))",
                    )
                except Exception:
                    links = []
                for link in links:
                    m = re.search(r"/user/(MS4wLjAB[A-Za-z0-9_-]+)", str(link.get("href") or ""))
                    if m:
                        candidates.setdefault(m.group(1), {"sec_uid": m.group(1), "nickname": str(link.get("text") or "")})

                if not candidates:
                    try:
                        body = page.locator("body").inner_text(timeout=2000)
                    except Exception:
                        body = ""
                    if "登录后" in body or "扫码登录" in body:
                        raise CreatorAuthError("抖音搜索需要登录；请先在 App 导入抖音登录凭证")
                    raise CreatorNotFound(f"没有在抖音搜索到「{query}」对应的博主")

                q = query.casefold()
                exact_id = [
                    user
                    for user in candidates.values()
                    if q in {str(user.get("unique_id") or "").casefold(), str(user.get("short_id") or "").casefold()}
                ]
                exact_name = [
                    user for user in candidates.values() if str(user.get("nickname") or "").strip().casefold() == q
                ]
                if len(exact_id) == 1:
                    return exact_id[0]
                if len(exact_name) == 1:
                    return exact_name[0]
                if len(candidates) == 1:
                    return next(iter(candidates.values()))

                sample = "、".join(
                    f"{str(user.get('nickname') or '?')}({user.get('unique_id') or user.get('short_id') or '?'})"
                    for user in list(candidates.values())[:5]
                )
                raise CreatorNotFound(f"抖音搜索到多个可能结果，请改用抖音号：{sample}")
            finally:
                context.close()
                browser.close()

    def capture(self, ref: CreatorRef, *, need: int, cookie_file: Path | None) -> dict:
        try:
            from playwright.sync_api import sync_playwright
        except ImportError as exc:  # pragma: no cover - 依赖缺失时走这里
            raise CreatorUnavailable("未安装 Playwright：" + _BROWSER_INSTALL_HINT) from exc

        cookies = _parse_cookie_file(cookie_file)
        if not cookies.get("sessionid"):
            # 主页作品页在部分出口 IP 上未登录也能加载；不再直接拒绝，
            # 失败时由页面状态给出验证码/登录提示。
            log.info("抖音未登录，尝试用访客 Cookie 打开博主主页")

        captured: dict[str, dict] = {}
        meta: dict[str, Any] = {"profile": None, "has_more": True, "cursor": 0, "error": None}

        def on_response(response) -> None:
            url = response.url or ""
            if "/aweme/v1/web/aweme/post/" in url:
                try:
                    data = response.json()
                except Exception:
                    return
                if not isinstance(data, dict):
                    return
                if data.get("status_code") not in (0, None):
                    meta["error"] = str(data.get("status_msg") or data.get("status_code"))
                    return
                for aweme in data.get("aweme_list") or []:
                    if isinstance(aweme, dict) and aweme.get("aweme_id") and _has_video(aweme):
                        captured.setdefault(str(aweme["aweme_id"]), aweme)
                        if meta["profile"] is None and isinstance(aweme.get("author"), dict):
                            meta["profile"] = aweme["author"]
                meta["has_more"] = bool(data.get("has_more"))
                meta["cursor"] = _int(data.get("max_cursor")) or 0
            elif "/aweme/v1/web/user/profile/other/" in url:
                try:
                    data = response.json()
                except Exception:
                    return
                user = (data or {}).get("user") if isinstance(data, dict) else None
                if isinstance(user, dict):
                    meta["profile"] = user

        max_items = min(max(need + 18, 36), 120)
        timeout_ms = int(settings.douyin_browser_timeout * 1000)

        with sync_playwright() as playwright:
            launch_options = {
                "headless": bool(settings.douyin_browser_headless),
                "args": ["--disable-blink-features=AutomationControlled", "--disable-dev-shm-usage", "--no-sandbox"],
            }
            if settings.douyin_browser_channel:
                launch_options["channel"] = settings.douyin_browser_channel
            browser = playwright.chromium.launch(**launch_options)
            context = browser.new_context(
                user_agent=_UA,
                locale="zh-CN",
                viewport={"width": 1536, "height": 864},
            )
            try:
                # 访客 ttwid/msToken 在部分出口上反而更容易触发验证码；
                # 没有登录 Cookie 时用干净的浏览器上下文。
                if cookies.get("sessionid"):
                    context.add_cookies(
                        [
                            {
                                "name": name,
                                "value": value,
                                "domain": ".douyin.com",
                                "path": "/",
                                "secure": True,
                                "sameSite": "Lax",
                            }
                            for name, value in cookies.items()
                            if name and value
                        ]
                    )
                page = context.new_page()
                page.on("response", on_response)
                try:
                    page.goto(ref.url, wait_until="domcontentloaded", timeout=timeout_ms)
                except Exception as exc:
                    log.warning("抖音主页打开超时/失败: %s", exc)
                page.wait_for_timeout(1500)

                blocked = _blocked_reason(page)
                if blocked:
                    if settings.douyin_browser_headless:
                        raise CreatorUnavailable(
                            f"抖音要求安全验证（{blocked}），无头浏览器无法完成。"
                            "请在本机用有头模式完成一次验证，或重新导入抖音 Cookie。"
                        )
                    # 有头模式：给用户最多两分钟完成验证。
                    deadline = time.time() + 120
                    while time.time() < deadline and _blocked_reason(page):
                        page.wait_for_timeout(2000)
                    if _blocked_reason(page):
                        raise CreatorUnavailable("抖音安全验证未完成，请重试")

                for _ in range(max(1, int(settings.douyin_browser_max_scrolls))):
                    if len(captured) >= max_items and not meta["has_more"]:
                        break
                    try:
                        page.evaluate("window.scrollTo(0, document.body.scrollHeight)")
                    except Exception:
                        pass
                    page.wait_for_timeout(1200)
                    if len(captured) >= max_items:
                        break
                    if captured and not meta["has_more"]:
                        break
                    blocked = _blocked_reason(page)
                    if blocked:
                        raise CreatorUnavailable(f"抖音页面触发安全验证（{blocked}），请重试或更换 Cookie")

                if not captured:
                    reason = meta.get("error") or _blocked_reason(page) or "页面没有返回作品列表"
                    raise CreatorUnavailable(f"抖音主页没有拿到作品列表（{reason}）。{_BROWSER_INSTALL_HINT}")

                profile_raw = meta.get("profile") or next(iter(captured.values())).get("author") or {}
                return {
                    "profile": profile_raw,
                    "items": list(captured.values()),
                    "has_more": bool(meta["has_more"]),
                }
            finally:
                context.close()
                browser.close()


def _blocked_reason(page) -> str | None:
    try:
        title = page.title()
    except Exception:
        title = ""
    if "验证码" in title or "安全验证" in title:
        return title.strip()[:80] or "验证码"
    try:
        body = page.locator("body").inner_text(timeout=2000)[:5000]
    except Exception:
        body = ""
    for marker in ("验证码中间页", "安全验证", "验证后继续", "请完成验证"):
        if marker in body:
            return marker
    return None


class DouyinCreatorProvider:
    platform = Platform.DOUYIN

    def __init__(self, *, client_factory=None, bridge_factory=None):
        self._client_factory = client_factory or httpx.Client
        self._bridge_factory = bridge_factory or DouyinBrowserBridge

    # ---------- 用户输入 ----------

    def resolve(self, value: str, *, cookie_file: Path | None) -> CreatorRef:
        value = (value or "").strip()
        if _SEC_UID_RE.match(value):
            return CreatorRef(Platform.DOUYIN, value, f"https://www.douyin.com/user/{value}", value)
        if value.isdigit():
            try:
                return self._resolve_unique_id(value, cookie_file)
            except CreatorNotFound:
                pass
            except CreatorUnavailable:
                if not settings.douyin_browser:
                    raise
        if not settings.douyin_browser:
            raise CreatorUnavailable("抖音按抖音号/昵称搜索需要启用浏览器桥接：" + _BROWSER_INSTALL_HINT)
        user = self._bridge_factory().resolve_user(value, cookie_file=cookie_file)
        sec = user.get("sec_uid") or user.get("sec_user_id")
        if not isinstance(sec, str) or not _SEC_UID_RE.match(sec):
            raise CreatorNotFound(f"抖音没有返回「{value}」的主页标识")
        return CreatorRef(Platform.DOUYIN, sec, f"https://www.douyin.com/user/{sec}", value)

    def _resolve_unique_id(self, unique_id: str, cookie_file: Path | None) -> CreatorRef:
        """抖音号先在公开 user/info 接口换成 sec_uid；这条路不需要登录。"""
        cookies = _parse_cookie_file(cookie_file)
        headers = {
            "User-Agent": _UA,
            "Referer": "https://www.douyin.com/",
            "Accept": "application/json, text/plain, */*",
        }
        try:
            with self._client_factory(
                timeout=settings.http_timeout, headers=headers, cookies=cookies, follow_redirects=True
            ) as client:
                resp = client.get(API_USER_INFO, params={"unique_id": unique_id})
                data = resp.json()
        except Exception as exc:
            raise CreatorUnavailable(f"抖音号查询失败：{exc}") from exc

        if data.get("status_code") not in (0, None):
            raise CreatorNotFound(f"没有找到抖音号 {unique_id} 对应的博主")
        user = data.get("user_info") or {}
        sec = user.get("sec_uid")
        if not isinstance(sec, str) or not _SEC_UID_RE.match(sec):
            raise CreatorNotFound(f"没有找到抖音号 {unique_id} 对应的主页")
        return CreatorRef(Platform.DOUYIN, sec, f"https://www.douyin.com/user/{sec}", unique_id)

    # ---------- 窗口 ----------

    def fetch_window(
        self,
        ref: CreatorRef,
        *,
        start: int,
        count: int,
        cookie_file: Path | None,
    ) -> CreatorWindow:
        need = start + count - 1
        direct_error: Exception | None = None
        catalog: dict | None = None

        try:
            catalog = self._fetch_direct(ref, need=need, cookie_file=cookie_file)
        except CreatorAuthError:
            raise
        except CreatorUnavailable as exc:
            direct_error = exc

        if catalog is None and settings.douyin_browser:
            log.info("抖音直连不可用，尝试浏览器桥接: %s", direct_error)
            try:
                catalog = self._bridge_factory().capture(ref, need=need, cookie_file=cookie_file)
            except CreatorError:
                raise
            except Exception as exc:
                log.warning("抖音浏览器桥接失败: %s", exc)
                text = str(exc)
                if "playwright install" in text or "Executable doesn't exist" in text:
                    text = _BROWSER_INSTALL_HINT
                raise CreatorUnavailable(f"抖音浏览器桥接失败：{text}") from exc

        if catalog is None:
            raise direct_error or CreatorUnavailable(_BROWSER_INSTALL_HINT)

        # 抖音主页会把置顶作品放在列表最前面；范围语义是「按发布时间倒序」，
        # 所以这里必须重新按 create_time 排序，不能直接信任页面顺序。
        items = sorted(
            catalog.get("items") or [],
            key=lambda item: _int(item.get("create_time")) or 0,
            reverse=True,
        )
        profile_raw = catalog.get("profile") or {}
        profile = self._profile(ref, profile_raw, len(items), bool(catalog.get("has_more")))
        # 图集/图文作品没有视频流，已在采集时跳过；如果已经翻到底，
        # 用实际可下载条数覆盖作者的“作品数”，避免范围判断把图集算进去。
        total = len(items) if not catalog.get("has_more") else profile.total_videos

        if total is not None and start > total:
            raise CreatorRangeError(f"范围超出：该博主只有 {total} 条公开视频")
        if start > len(items):
            if catalog.get("has_more"):
                raise CreatorUnavailable(f"只抓取到 {len(items)} 条视频，不足以覆盖起点 {start}；请缩小起点后重试")
            raise CreatorRangeError(f"范围超出：该博主只有 {len(items)} 条公开视频")

        videos = [self._to_video(raw, index + 1, ref) for index, raw in enumerate(items)]
        has_more = bool(catalog.get("has_more")) or (total is not None and len(videos) < total)
        return CreatorWindow(
            profile=profile,
            items=videos[start - 1 : start - 1 + count],
            total=total if total is not None else len(videos),
            has_more=has_more,
        )

    # ---------- 直连 ----------

    def _fetch_direct(self, ref: CreatorRef, *, need: int, cookie_file: Path | None) -> dict:
        cookies = _parse_cookie_file(cookie_file)
        headers = {
            "User-Agent": _UA,
            "Referer": ref.url,
            "Accept": "application/json, text/plain, */*",
            "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
        }
        collected: dict[str, dict] = {}
        has_more = True
        cursor = 0
        pages = 0
        with self._client_factory(
            timeout=settings.http_timeout, headers=headers, cookies=cookies, follow_redirects=True
        ) as client:
            while len(collected) < need and has_more and pages < 12:
                params = {
                    **_BASE_PARAMS,
                    "sec_user_id": ref.creator_id,
                    "max_cursor": str(cursor),
                    "locate_query": "false",
                    "show_live_replay_strategy": "1",
                    "need_time_list": "1",
                    "time_list_query": "0",
                    "whale_cut_token": "",
                    "cut_version": "1",
                    "count": "18",
                    "publish_video_strategy_type": "2",
                    "msToken": cookies.get("msToken", ""),
                    "uifid": cookies.get("uifid", ""),
                    "webid": cookies.get("webid", ""),
                }
                try:
                    resp = client.get(API_POST, params=params)
                except Exception as exc:
                    raise CreatorUnavailable(f"抖音主页请求失败：{exc}") from exc

                if resp.status_code == 403:
                    body = (resp.text or "")[:160]
                    if "ArgusSecurityPlugin" in body:
                        raise CreatorUnavailable(
                            f"抖音主页接口被 Argus 风控拦截（{body.strip()}）。{_BROWSER_INSTALL_HINT}"
                        )
                    raise CreatorUnavailable(f"抖音主页请求被拒绝（HTTP 403）：{body.strip()}")

                try:
                    data = resp.json()
                except Exception as exc:
                    raise CreatorUnavailable(f"抖音主页返回了非 JSON（HTTP {resp.status_code}），大概率被风控") from exc

                status = data.get("status_code")
                if status not in (0, None):
                    message = str(data.get("status_msg") or status)
                    if status in (2483,) or "登录" in message:
                        raise CreatorAuthError(f"抖音要求登录：{message}，请重新导入抖音 Cookie")
                    raise CreatorUnavailable(f"抖音主页接口返回错误：{message}")

                page_items = data.get("aweme_list") or []
                for aweme in page_items:
                    if isinstance(aweme, dict) and aweme.get("aweme_id") and _has_video(aweme):
                        collected.setdefault(str(aweme["aweme_id"]), aweme)
                has_more = bool(data.get("has_more"))
                next_cursor = _int(data.get("max_cursor")) or 0
                pages += 1
                if not page_items or (next_cursor and next_cursor == cursor):
                    break
                cursor = next_cursor

        if not collected:
            raise CreatorNotFound("该抖音博主没有可读取的公开作品，或主页不可见")
        return {"profile": next(iter(collected.values())).get("author") or {}, "items": list(collected.values()), "has_more": has_more}

    # ---------- 字段映射 ----------

    def _profile(self, ref: CreatorRef, raw: dict, captured: int, has_more: bool) -> CreatorProfile:
        raw = raw if isinstance(raw, dict) else {}
        total = _int(raw.get("aweme_count"))
        if total == 0:
            total = None
        if total is None and not has_more:
            total = captured
        return CreatorProfile(
            platform=Platform.DOUYIN,
            creator_id=str(raw.get("sec_uid") or ref.creator_id),
            name=str(raw.get("nickname") or "").strip() or f"抖音博主 {ref.creator_id[-8:]}",
            url=ref.url,
            avatar_url=_first_url(raw.get("avatar_thumb")) or _first_url(raw.get("avatar_medium")),
            total_videos=total,
            follower_count=_int(raw.get("follower_count")),
            signature=(raw.get("signature") or "").strip() or None,
        )

    def _to_video(self, raw: dict, position: int, ref: CreatorRef) -> CreatorVideo:
        video = raw.get("video") if isinstance(raw.get("video"), dict) else {}
        stats_raw = raw.get("statistics") if isinstance(raw.get("statistics"), dict) else {}
        desc = (raw.get("desc") or "").strip()
        duration_ms = video.get("duration") or raw.get("duration")
        try:
            duration = float(duration_ms) / 1000 if duration_ms else None
        except (TypeError, ValueError):
            duration = None
        aweme_id = str(raw.get("aweme_id"))
        return CreatorVideo(
            platform=Platform.DOUYIN,
            video_id=aweme_id,
            title=desc[:60] or f"douyin-{aweme_id}",
            source_url=f"https://www.douyin.com/video/{aweme_id}",
            position=position,
            creator_id=ref.creator_id,
            cover_url=_cover(video),
            description=desc or None,
            duration_sec=duration,
            published_at=_iso(raw.get("create_time")),
            stats=VideoStats(
                view_count=_int(stats_raw.get("play_count")),
                like_count=_int(stats_raw.get("digg_count")),
                comment_count=_int(stats_raw.get("comment_count")),
                share_count=_int(stats_raw.get("share_count")),
                favorite_count=_int(stats_raw.get("collect_count")),
            ),
            prefetched=raw,
        )

    # ---------- 互动数据 ----------

    def enrich(
        self,
        video: CreatorVideo,
        *,
        include_stats: bool,
        include_comments: bool,
        comment_limit: int,
        cookie_file: Path | None,
    ) -> CreatorVideo:
        # 作品列表本身已经带 statistics；抖音评论正文当前不做批量抓取，
        # 只保留 comment_count，避免把「评论数」说成「评论内容」。
        if include_comments:
            video.comments = []
            video.comments_status = "count_only"
        return video
