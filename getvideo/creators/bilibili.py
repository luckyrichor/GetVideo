"""B 站 UP 主空间适配器。

空间列表走 ``x/space/wbi/arc/search``（需要 WBI 签名 + dm 指纹参数），
单条互动数据走 ``x/web-interface/view``，热门评论走 ``x/v2/reply``。
这些都是公开 Web 接口；登录凭证只用于提高风控通过率，不参与解析。
"""
from __future__ import annotations

import html
import logging
import re
import time
from datetime import datetime, timezone
from pathlib import Path

import httpx

from ..config import settings
from ..extractors.bilibili import (
    _get_mixin_key,
    _parse_cookie_file,
    _sign,
    dm_params,
    reset_wbi_cache,
)
from ..guest import ensure_guest_cookies
from ..sharelink import CreatorRef, Platform
from .base import (
    CreatorAuthError,
    CreatorComment,
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

API_SPACE = "https://api.bilibili.com/x/space/wbi/arc/search"
API_CARD = "https://api.bilibili.com/x/web-interface/card"
API_VIEW = "https://api.bilibili.com/x/web-interface/view"
API_REPLY = "https://api.bilibili.com/x/v2/reply"
API_SEARCH_USER = "https://api.bilibili.com/x/web-interface/search/type"

_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)

_PAGE_SIZE = 20
_MAX_PAGES = 30


def _iso(ts: int | None) -> str | None:
    if not ts:
        return None
    try:
        return datetime.fromtimestamp(int(ts), timezone.utc).isoformat()
    except (OSError, OverflowError, ValueError):
        return None


def _https(url: str | None) -> str | None:
    if not url:
        return None
    if url.startswith("//"):
        return "https:" + url
    if url.startswith("http://"):
        return "https://" + url[7:]
    return url


def _duration(text: str | None) -> float | None:
    if not text:
        return None
    try:
        parts = [int(p) for p in str(text).split(":")]
    except ValueError:
        return None
    if not parts or len(parts) > 3:
        return None
    seconds = 0
    for part in parts:
        seconds = seconds * 60 + part
    return float(seconds)


def _int(value) -> int | None:
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _clean_name(value) -> str:
    return re.sub(r"<[^>]+>", "", html.unescape(str(value or ""))).strip()


class BilibiliCreatorProvider:
    platform = Platform.BILIBILI

    def __init__(self, *, client_factory=None):
        self._client_factory = client_factory or httpx.Client

    # ---------- 用户输入 ----------

    def resolve(self, value: str, *, cookie_file: Path | None) -> CreatorRef:
        value = (value or "").strip()
        if not value:
            raise CreatorNotFound("没有提供 B 站 UID 或昵称")
        if value.isdigit():
            # UID 直接拼空间地址；真正是否存在由后面的空间列表接口确认。
            return CreatorRef(Platform.BILIBILI, value, f"https://space.bilibili.com/{value}/video", value)

        cookies = _parse_cookie_file(cookie_file)
        headers = {
            "User-Agent": _UA,
            "Referer": "https://search.bilibili.com/",
            "Accept": "application/json, text/plain, */*",
        }
        try:
            with self._client_factory(
                timeout=settings.http_timeout, headers=headers, cookies=cookies, follow_redirects=True
            ) as client:
                resp = client.get(
                    API_SEARCH_USER,
                    params={"search_type": "bili_user", "keyword": value, "page": 1},
                )
                data = resp.json()
        except Exception as exc:
            raise CreatorUnavailable(f"B 站用户搜索失败：{exc}") from exc

        if data.get("code") != 0:
            raise CreatorUnavailable(f"B 站用户搜索返回错误：{data.get('message') or data.get('code')}")

        rows = ((data.get("data") or {}).get("result")) or []
        candidates: list[tuple[str, str]] = []
        for row in rows:
            if not isinstance(row, dict) or not row.get("mid"):
                continue
            candidates.append((str(row["mid"]), _clean_name(row.get("uname"))))

        exact = [item for item in candidates if item[1].casefold() == value.casefold()]
        if len(exact) == 1:
            mid, name = exact[0]
        elif len(candidates) == 1:
            mid, name = candidates[0]
        else:
            sample = "、".join(f"{name}({mid})" for mid, name in candidates[:5])
            raise CreatorNotFound(
                f"没有找到昵称完全匹配的 UP 主「{value}」" + (f"；候选：{sample}" if sample else "")
            )
        return CreatorRef(Platform.BILIBILI, mid, f"https://space.bilibili.com/{mid}/video", value)

    # ---------- 窗口 ----------

    def fetch_window(
        self,
        ref: CreatorRef,
        *,
        start: int,
        count: int,
        cookie_file: Path | None,
    ) -> CreatorWindow:
        cookies = _parse_cookie_file(cookie_file)
        headers = {
            "User-Agent": _UA,
            "Referer": ref.url,
            "Origin": "https://space.bilibili.com",
            "Accept": "application/json, text/plain, */*",
            "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
        }
        with self._client_factory(
            timeout=settings.http_timeout,
            headers=headers,
            cookies=cookies,
            follow_redirects=True,
        ) as client:
            profile = self._fetch_profile(client, ref)
            videos, total, has_more = self._fetch_items(client, ref, start, count, cookie_file)
            if not videos:
                if total is not None and start > total:
                    raise CreatorRangeError(f"范围超出：该 UP 主只有 {total} 条公开视频")
                raise CreatorNotFound("该 UP 主没有可读取的公开视频")
            profile.total_videos = profile.total_videos or total
            return CreatorWindow(profile=profile, items=videos, total=total, has_more=has_more)

    # ---------- 主页 ----------

    def _fetch_profile(self, client: httpx.Client, ref: CreatorRef) -> CreatorProfile:
        fallback = CreatorProfile(
            platform=Platform.BILIBILI,
            creator_id=ref.creator_id,
            name=f"UP {ref.creator_id}",
            url=ref.url,
        )
        try:
            resp = client.get(API_CARD, params={"mid": ref.creator_id})
            data = resp.json()
        except Exception as exc:
            log.debug("B 站 card 接口失败，使用空间列表兜底: %s", exc)
            return fallback

        if data.get("code") != 0:
            log.debug("B 站 card 接口返回 %s", data.get("code"))
            return fallback

        payload = data.get("data") or {}
        card = payload.get("card") or {}
        name = str(card.get("name") or fallback.name).strip()
        return CreatorProfile(
            platform=Platform.BILIBILI,
            creator_id=str(card.get("mid") or ref.creator_id),
            name=name or fallback.name,
            url=ref.url,
            avatar_url=_https(card.get("face")),
            total_videos=_int(payload.get("archive_count")),
            follower_count=_int(payload.get("follower") or card.get("fans")),
            signature=(card.get("sign") or "").strip() or None,
        )

    # ---------- 空间列表 ----------

    def _fetch_items(
        self,
        client: httpx.Client,
        ref: CreatorRef,
        start: int,
        count: int,
        cookie_file: Path | None,
    ) -> tuple[list[CreatorVideo], int | None, bool]:
        need = start + count - 1
        collected: list[CreatorVideo] = []
        total: int | None = None
        has_more = True
        page = 1
        while page <= _MAX_PAGES and len(collected) < need and has_more:
            data = self._space_page(client, ref, page, cookie_file)
            page_info = data.get("page") or {}
            total = _int(page_info.get("count")) or total
            vlist = ((data.get("list") or {}).get("vlist")) or []
            for raw in vlist:
                # 隐藏合集占位没有 bvid，不能算作一条视频。
                if not isinstance(raw, dict) or not raw.get("bvid"):
                    continue
                collected.append(self._to_video(raw, len(collected) + 1, ref))
            page_end = _int(page_info.get("pn")) or page
            page_size = _int(page_info.get("ps")) or _PAGE_SIZE
            has_more = bool(vlist) and (total is None or page_end * page_size < total)
            page += 1

        if total is not None and start > total:
            return [], total, False
        selected = collected[start - 1 : start - 1 + count]
        return selected, total, has_more

    def _space_page(
        self,
        client: httpx.Client,
        ref: CreatorRef,
        page: int,
        cookie_file: Path | None,
    ) -> dict:
        params = {
            "keyword": "",
            "mid": ref.creator_id,
            "order": "pubdate",
            "order_avoided": "true",
            "platform": "web",
            "pn": page,
            "ps": _PAGE_SIZE,
            "tid": 0,
            "web_location": "333.1387",
            "special_type": "",
            "index": 0,
            **dm_params(),
        }

        last_error: str | None = None
        for attempt in range(3):
            try:
                signed = _sign(params, _get_mixin_key(client))
                resp = client.get(API_SPACE, params=signed)
            except CreatorError:
                raise
            except Exception as exc:
                last_error = f"空间列表请求失败：{exc}"
                resp = None

            if resp is not None:
                if resp.status_code == 412:
                    last_error = "B 站空间接口触发风控（HTTP 412）"
                else:
                    try:
                        data = resp.json()
                    except Exception:
                        last_error = "B 站空间接口返回了非 JSON（大概率被风控）"
                        data = None
                    if isinstance(data, dict):
                        code = data.get("code")
                        if code == 0:
                            return data.get("data") or {}
                        message = data.get("message") or f"code={code}"
                        if code in (-412, -352, -401):
                            last_error = f"B 站空间接口触发风控（{code} {message}）"
                        elif code == -404:
                            raise CreatorNotFound("该 UP 主不存在或主页不可见")
                        elif code in (-403, 62002):
                            raise CreatorAuthError(f"该 UP 主空间不可访问（{message}）")
                        else:
                            last_error = f"B 站空间接口返回错误：{message}"

            # 访客 cookie 可以换一份再试；用户自己的凭证不自动刷新。
            if attempt < 2:
                reset_wbi_cache()
                if _is_guest_cookie(cookie_file):
                    refreshed = ensure_guest_cookies(Platform.BILIBILI, force=True)
                    fresh = _parse_cookie_file(refreshed)
                    if fresh:
                        client.cookies.update(fresh)
                time.sleep(1.5)
                continue
            break

        raise CreatorUnavailable(last_error or "B 站空间列表不可用")

    # ---------- 单条视频 ----------

    def enrich(
        self,
        video: CreatorVideo,
        *,
        include_stats: bool,
        include_comments: bool,
        comment_limit: int,
        cookie_file: Path | None,
    ) -> CreatorVideo:
        if not include_stats and not include_comments:
            return video

        cookies = _parse_cookie_file(cookie_file)
        headers = {
            "User-Agent": _UA,
            "Referer": video.source_url,
            "Origin": "https://www.bilibili.com",
            "Accept": "application/json, text/plain, */*",
        }
        try:
            with self._client_factory(
                timeout=settings.http_timeout, headers=headers, cookies=cookies, follow_redirects=True
            ) as client:
                if include_stats:
                    self._fill_stats(client, video)
                if include_comments:
                    self._fill_comments(client, video, comment_limit)
        except CreatorError:
            raise
        except Exception as exc:
            log.warning("B 站视频 %s 互动数据抓取失败: %s", video.video_id, exc)
        return video

    def _fill_stats(self, client: httpx.Client, video: CreatorVideo) -> None:
        try:
            resp = client.get(API_VIEW, params={"bvid": video.video_id})
            data = resp.json()
        except Exception as exc:
            log.debug("B 站 view 接口失败 %s: %s", video.video_id, exc)
            return
        if data.get("code") != 0:
            return
        payload = data.get("data") or {}
        stat = payload.get("stat") or {}
        video.stats = VideoStats(
            view_count=_int(stat.get("view")),
            like_count=_int(stat.get("like")),
            comment_count=_int(stat.get("reply")),
            share_count=_int(stat.get("share")),
            favorite_count=_int(stat.get("favorite")),
            coin_count=_int(stat.get("coin")),
            danmaku_count=_int(stat.get("danmaku")),
        )
        # 列表接口偶尔没有完整标题/简介，view 接口更权威。
        video.title = str(payload.get("title") or video.title)
        video.description = payload.get("desc") or video.description
        video.cover_url = _https(payload.get("pic")) or video.cover_url
        video.duration_sec = _int(payload.get("duration")) or video.duration_sec
        video.published_at = _iso(payload.get("pubdate")) or video.published_at
        video.platform_oid = _int(payload.get("aid")) or video.platform_oid

    def _fill_comments(self, client: httpx.Client, video: CreatorVideo, limit: int) -> None:
        oid = video.platform_oid
        if not oid:
            video.comments_status = "missing_aid"
            return
        try:
            resp = client.get(
                API_REPLY,
                params={"type": 1, "oid": oid, "pn": 1, "ps": max(1, min(limit, 20)), "sort": 2},
            )
            data = resp.json()
        except Exception as exc:
            video.comments_status = f"unavailable: {exc}"
            return
        if data.get("code") != 0:
            video.comments_status = f"unavailable: code={data.get('code')}"
            return
        payload = data.get("data") or {}
        replies = payload.get("replies") or []
        comments: list[CreatorComment] = []
        for reply in replies:
            if not isinstance(reply, dict):
                continue
            content = ((reply.get("content") or {}).get("message") or "").strip()
            if not content:
                continue
            member = reply.get("member") or {}
            comments.append(
                CreatorComment(
                    author=str(member.get("uname") or "B站用户"),
                    content=content,
                    like_count=_int(reply.get("like")),
                    created_at=_iso(reply.get("ctime")),
                    reply_count=_int(reply.get("rcount")),
                )
            )
        video.comments = comments
        video.comments_status = "ok"

    # ---------- 字段映射 ----------

    def _to_video(self, raw: dict, position: int, ref: CreatorRef) -> CreatorVideo:
        # vlist 已经带播放/弹幕/评论数；点赞等要等 view 接口补齐。
        return CreatorVideo(
            platform=Platform.BILIBILI,
            video_id=str(raw.get("bvid")),
            title=str(raw.get("title") or f"bilibili-{raw.get('bvid')}"),
            source_url=f"https://www.bilibili.com/video/{raw.get('bvid')}",
            position=position,
            creator_id=ref.creator_id,
            cover_url=_https(raw.get("pic")),
            description=(raw.get("description") or "").strip() or None,
            duration_sec=_duration(raw.get("length")),
            published_at=_iso(raw.get("created")),
            stats=VideoStats(
                view_count=_int(raw.get("play")),
                comment_count=_int(raw.get("comment")),
                danmaku_count=_int(raw.get("video_review")),
            ),
            platform_oid=_int(raw.get("aid")),
        )


def _is_guest_cookie(path: Path | None) -> bool:
    return bool(path and path.name.startswith("_guest__"))
