"""分享文本 → 规范化视频地址。

用户从 App「复制链接」粘过来的东西长这样：

    7.53 复制打开抖音，看看【作者】的作品... https://v.douyin.com/iAbCdEf/ 复制此链接...
    【标题-哔哩哔哩】 https://b23.tv/AbCdEf

所以不能直接喂给下载器：要先从废话里抠出 URL、跟完 302、再规范化成
下载器认得的形式。
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from enum import Enum

import httpx

from .config import settings


class Platform(str, Enum):
    BILIBILI = "bilibili"
    DOUYIN = "douyin"


# 抖音的分享文案会把中文直接怼在 URL 后面，所以字符集要收紧，
# 不能用贪婪的 \S+ —— 否则会把「复制此链接」也吃进 URL。
URL_RE = re.compile(r"https?://[A-Za-z0-9\-._~:/?#\[\]@!$&'()*+,;=%]+")

BV_RE = re.compile(r"\bBV[0-9A-Za-z]{10}\b")
AV_RE = re.compile(r"\bav(\d{1,15})\b", re.IGNORECASE)

_BILI_HOSTS = ("bilibili.com", "b23.tv", "bili2233.cn", "acg.tv")
_DOUYIN_HOSTS = ("douyin.com", "iesdouyin.com")

_DOUYIN_VIDEO_ID_RE = re.compile(r"/(?:video|note|share/video)/(\d+)")

# 各平台分享链接都会挂一堆追踪参数；去掉它们能让同一个视频产生同一个 URL，
# 去重才不会漏。
_JUNK_QUERY_PREFIXES = ("vd_source", "spm_id", "share_", "from", "seid", "buvid", "unique_k", "region")


@dataclass(frozen=True)
class ResolvedLink:
    platform: Platform
    video_id: str
    url: str
    raw_url: str
    page: int = 1

    @property
    def track_key(self) -> str:
        """分 P 是不同视频；P1 仍沿用旧键，保持已有记录可复用。"""
        if self.platform is Platform.BILIBILI and self.page > 1:
            return f"{self.video_id}-p{self.page}"
        return self.video_id

    def __str__(self) -> str:  # pragma: no cover - 仅日志用
        return f"{self.platform.value}:{self.video_id}"


@dataclass(frozen=True)
class CreatorRef:
    """博主主页：批量任务只认主页，不认单条视频。"""

    platform: Platform
    creator_id: str
    url: str
    raw_url: str = ""

    def __str__(self) -> str:  # pragma: no cover - 仅日志用
        return f"{self.platform.value}:{self.creator_id}"


class LinkError(ValueError):
    """分享文本里找不到可用的视频链接。"""


@dataclass(frozen=True)
class CreatorInput:
    """手机用户常给的内容：抖音号、B 站 UID、昵称或完整主页链接。"""

    platform: Platform | None
    value: str
    kind: str  # numeric | sec_uid | nickname | url


_DOUYIN_LABEL_RE = re.compile(
    r"(?:抖音号|抖音id|抖音ID|抖音|douyin(?:\s*id)?)\s*(?:[:：=\-]\s*)?([^\s:：=\-]{1,64})",
    re.IGNORECASE,
)
_BILI_LABEL_RE = re.compile(
    r"(?:b站|哔哩哔哩|bilibili)\s*(?:uid|id|号|用户)?\s*(?:[:：=\-]\s*)?([^\s:：=\-]{1,64})",
    re.IGNORECASE,
)
_UID_LABEL_RE = re.compile(r"\buid\s*(?:[:：=\-]\s*)?(\d{1,20})", re.IGNORECASE)
_SEC_UID_RE = re.compile(r"^MS4wLjAB[A-Za-z0-9_-]{8,}$")


def _platform_of_value(value) -> Platform | None:
    if value is None:
        return None
    if isinstance(value, Platform):
        return value
    try:
        return Platform(str(value).strip().lower())
    except ValueError:
        raise LinkError(f"不支持的平台：{value}") from None


def parse_creator_input(text: str, platform=None) -> CreatorInput:
    """把「抖音号：xxx」「B站UID：xxx」「昵称」解析成统一查询。

    带 URL 的输入仍由 ``resolve_creator()`` 处理；这里只负责非 URL 的
    手机端常见输入。平台明确冲突时直接报错，避免悄悄查错账号。
    """
    raw = (text or "").strip()
    if not raw:
        raise LinkError("输入为空")

    hint = _platform_of_value(platform)
    label_platform: Platform | None = None
    value: str | None = None

    if m := _DOUYIN_LABEL_RE.search(raw):
        label_platform, value = Platform.DOUYIN, m.group(1)
    elif m := _BILI_LABEL_RE.search(raw):
        label_platform, value = Platform.BILIBILI, m.group(1)
    elif m := _UID_LABEL_RE.search(raw):
        label_platform, value = Platform.BILIBILI, m.group(1)

    if label_platform is not None and hint is not None and label_platform is not hint:
        raise LinkError(f"输入写着 {label_platform.value}，但 platform 指定了 {hint.value}")
    effective = label_platform or hint
    value = (value if value is not None else raw).strip().strip('"\'“”')
    value = value.strip(" \t\r\n,，。;；")

    if not value:
        raise LinkError("没有识别出抖音号、B 站 UID 或昵称")
    if len(value) > 64:
        raise LinkError("博主标识太长，请传抖音号、B 站 UID 或主页链接")

    if _SEC_UID_RE.match(value):
        return CreatorInput(Platform.DOUYIN, value, "sec_uid")
    if value.isdigit():
        return CreatorInput(effective, value, "numeric")
    return CreatorInput(effective, value, "nickname")


def find_urls(text: str) -> list[str]:
    """从任意文本里抠出所有 http(s) 链接，按出现顺序。"""
    urls = []
    for m in URL_RE.finditer(text or ""):
        url = m.group(0).rstrip(".,;:!?)）】」》\"'")
        if url not in urls:
            urls.append(url)
    return urls


def _host_of(url: str) -> str:
    return httpx.URL(url).host.lower()


def _platform_of(url: str) -> Platform | None:
    host = _host_of(url)
    if any(host == h or host.endswith("." + h) for h in _BILI_HOSTS):
        return Platform.BILIBILI
    if any(host == h or host.endswith("." + h) for h in _DOUYIN_HOSTS):
        return Platform.DOUYIN
    return None


def _is_short(url: str) -> bool:
    host = _host_of(url)
    return host in ("b23.tv", "bili2233.cn", "v.douyin.com", "v.iesdouyin.com")


def expand(url: str, *, client: httpx.Client | None = None) -> str:
    """跟随 302 把短链展开成真实地址。

    用 GET 而不是 HEAD：b23.tv 对 HEAD 有时直接返回 200 不给 Location。
    """
    owns_client = client is None
    client = client or httpx.Client(
        timeout=settings.http_timeout,
        follow_redirects=True,
        max_redirects=settings.max_redirects,
        headers={"User-Agent": _UA},
    )
    try:
        resp = client.get(url)
        return str(resp.url)
    except httpx.HTTPError:
        # 展开失败不算致命：原地址有时也能直接下载
        return url
    finally:
        if owns_client:
            client.close()


_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)


def _clean_query(url: httpx.URL) -> httpx.URL:
    keep = {
        k: v
        for k, v in url.params.multi_items()
        if not any(k.startswith(p) for p in _JUNK_QUERY_PREFIXES)
    }
    return url.copy_with(query=None).copy_merge_params(keep) if keep else url.copy_with(query=None)


def _normalize_bilibili(url: str) -> ResolvedLink | None:
    u = httpx.URL(url)
    raw_page = u.params.get("p", "1")
    if not raw_page.isdecimal() or int(raw_page) < 1:
        raise LinkError("B 站分 P 参数 p 必须是正整数")
    page = int(raw_page)
    bv = BV_RE.search(str(u))
    if bv:
        vid = bv.group(0)
        clean = _clean_query(httpx.URL(f"https://www.bilibili.com/video/{vid}"))
        if page > 1:
            clean = clean.copy_merge_params({"p": str(page)})
        return ResolvedLink(Platform.BILIBILI, vid, str(clean), url, page)
    if av := AV_RE.search(str(u)):
        vid = f"av{av.group(1)}"
        clean = f"https://www.bilibili.com/video/{vid}"
        if page > 1:
            clean += f"?p={page}"
        return ResolvedLink(Platform.BILIBILI, vid, clean, url, page)
    return None


def _normalize_douyin(url: str) -> ResolvedLink | None:
    u = httpx.URL(url)
    if m := _DOUYIN_VIDEO_ID_RE.search(u.path):
        vid = m.group(1)
        return ResolvedLink(Platform.DOUYIN, vid, f"https://www.douyin.com/video/{vid}", url)
    # 展开后偶尔落在 /user/xxx?modal_id=<aweme_id> 这种形式
    if modal := u.params.get("modal_id"):
        if modal.isdigit():
            return ResolvedLink(Platform.DOUYIN, modal, f"https://www.douyin.com/video/{modal}", url)
    return None


_BILI_SPACE_RE = re.compile(r"^/(\d{1,20})(?:/(?:upload/)?video)?/?$")
_DOUYIN_USER_RE = re.compile(r"^/(?:share/)?user/([A-Za-z0-9_-]{8,})/?$")


def _creator_from_url(url: str) -> CreatorRef | None:
    u = httpx.URL(url)
    host = (u.host or "").lower()
    path = u.path or "/"

    if any(host == h or host.endswith("." + h) for h in _BILI_HOSTS):
        mid = None
        if host == "space.bilibili.com":
            if m := _BILI_SPACE_RE.match(path):
                mid = m.group(1)
        elif m := re.match(r"^/space/(\d{1,20})/?$", path):
            mid = m.group(1)
        if mid:
            return CreatorRef(Platform.BILIBILI, mid, f"https://space.bilibili.com/{mid}/video", url)

    if any(host == h or host.endswith("." + h) for h in _DOUYIN_HOSTS):
        if m := _DOUYIN_USER_RE.match(path):
            sec_uid = m.group(1)
            return CreatorRef(Platform.DOUYIN, sec_uid, f"https://www.douyin.com/user/{sec_uid}", url)

    return None


def resolve_creator(text: str, *, client: httpx.Client | None = None) -> CreatorRef:
    """分享文本 / 主页链接 → CreatorRef。

    只接受博主主页；单条视频链接会给出明确提示，避免用户把视频地址
    当成博主地址提交后拿到一个空任务。
    """
    text = (text or "").strip()
    if not text:
        raise LinkError("输入为空")

    candidates = find_urls(text)
    if not candidates:
        raise LinkError("没有找到博主主页链接")

    errors: list[str] = []
    for raw in candidates:
        platform = _platform_of(raw)
        if platform is None:
            errors.append(f"不支持的站点: {_host_of(raw)}")
            continue

        url = expand(raw, client=client) if _is_short(raw) else raw
        platform = _platform_of(url) or platform
        if creator := _creator_from_url(url):
            return creator

        # 给出比「解析不出 id」更有用的提示。
        if platform is Platform.BILIBILI and (BV_RE.search(url) or AV_RE.search(url)):
            errors.append("这是单个视频链接；批量下载请传 UP 主空间主页（space.bilibili.com/<mid>）")
        elif platform is Platform.DOUYIN and _DOUYIN_VIDEO_ID_RE.search(httpx.URL(url).path):
            errors.append("这是单个视频链接；批量下载请传抖音博主主页（douyin.com/user/<sec_uid>）")
        else:
            errors.append(f"{platform.value} 链接里解析不出博主 id: {url}")

    raise LinkError("; ".join(errors) or "没有找到支持的博主主页链接")


def resolve(text: str, *, client: httpx.Client | None = None) -> ResolvedLink:
    """分享文本 → ResolvedLink。找不到就抛 LinkError。"""
    text = (text or "").strip()
    if not text:
        raise LinkError("输入为空")

    candidates = find_urls(text)

    # 没有 URL 时兜一手：用户可能直接贴了 BV 号
    if not candidates:
        if bv := BV_RE.search(text):
            return ResolvedLink(Platform.BILIBILI, bv.group(0), f"https://www.bilibili.com/video/{bv.group(0)}", text)
        if av := AV_RE.search(text):
            vid = f"av{av.group(1)}"
            return ResolvedLink(Platform.BILIBILI, vid, f"https://www.bilibili.com/video/{vid}", text)
        raise LinkError("文本里没有找到链接或 BV 号")

    errors: list[str] = []
    for raw in candidates:
        platform = _platform_of(raw)
        if platform is None:
            errors.append(f"不支持的站点: {_host_of(raw)}")
            continue

        url = expand(raw, client=client) if _is_short(raw) else raw
        # 展开后平台可能变化（b23.tv 只会跳 B 站，但抖音短链会跳到 iesdouyin）
        platform = _platform_of(url) or platform

        resolved = (
            _normalize_bilibili(url) if platform is Platform.BILIBILI else _normalize_douyin(url)
        )
        if resolved:
            return ResolvedLink(resolved.platform, resolved.video_id, resolved.url, raw, resolved.page)
        errors.append(f"{platform.value} 链接里解析不出视频 id: {url}")

    raise LinkError("; ".join(errors) or "无法解析")
