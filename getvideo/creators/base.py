"""博主批量任务的数据结构与平台适配接口。

平台只负责三件事：把主页解析成一份「按发布时间倒序」的视频窗口、
补齐单条视频的互动数据、按需抓评论。分页、范围裁剪、入库和下载都
在 service 层，平台适配器不碰数据库。
"""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Protocol

from ..sharelink import CreatorRef, Platform


class CreatorError(RuntimeError):
    """博主批量失败的基类；message 可以直接给用户看。"""


class CreatorUnavailable(CreatorError):
    """平台接口/浏览器桥接不可用（风控、缺依赖、服务端配置问题）。"""


class CreatorAuthError(CreatorError):
    """需要用户重新授权平台账号。"""


class CreatorNotFound(CreatorError):
    """博主不存在、主页不可见，或范围内没有视频。"""


class CreatorRangeError(CreatorError):
    """请求的序号范围超出博主作品数。"""


@dataclass
class VideoStats:
    view_count: int | None = None
    like_count: int | None = None
    comment_count: int | None = None
    share_count: int | None = None
    favorite_count: int | None = None
    coin_count: int | None = None
    danmaku_count: int | None = None


@dataclass
class CreatorComment:
    author: str
    content: str
    like_count: int | None = None
    created_at: str | None = None
    reply_count: int | None = None


@dataclass
class CreatorVideo:
    platform: Platform
    video_id: str
    title: str
    source_url: str
    position: int
    creator_id: str
    cover_url: str | None = None
    description: str | None = None
    duration_sec: float | None = None
    published_at: str | None = None
    stats: VideoStats = field(default_factory=VideoStats)
    comments: list[CreatorComment] = field(default_factory=list)
    comments_status: str | None = None
    # B 站评论接口要 aid；抖音用 aweme_id 即可。
    platform_oid: int | None = None
    # 抖音作品列表里已经带播放地址；下载时直接复用，避免再撞 aweme/detail。
    prefetched: dict | None = None


@dataclass
class CreatorProfile:
    platform: Platform
    creator_id: str
    name: str
    url: str
    avatar_url: str | None = None
    total_videos: int | None = None
    follower_count: int | None = None
    signature: str | None = None


@dataclass
class CreatorWindow:
    profile: CreatorProfile
    items: list[CreatorVideo]
    total: int | None
    has_more: bool


class CreatorProvider(Protocol):
    platform: Platform

    def resolve(self, value: str, *, cookie_file: Path | None) -> CreatorRef:
        """把抖音号 / B 站 UID / 昵称解析成主页。"""
        ...

    def fetch_window(
        self,
        ref: CreatorRef,
        *,
        start: int,
        count: int,
        cookie_file: Path | None,
    ) -> CreatorWindow:
        """返回从 start（1-based，最新一条为 1）开始的连续 count 条。"""
        ...

    def enrich(
        self,
        video: CreatorVideo,
        *,
        include_stats: bool,
        include_comments: bool,
        comment_limit: int,
        cookie_file: Path | None,
    ) -> CreatorVideo:
        """补齐点赞/评论等互动数据；失败时尽量保留已有字段。"""
        ...
