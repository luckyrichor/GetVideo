"""平台适配器注册表。

每次调用返回新实例，避免把某个用户的抖音浏览器会话/缓存带到下一个
请求里。
"""
from __future__ import annotations

from ..sharelink import Platform
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

_PROVIDERS = {
    Platform.BILIBILI: ("getvideo.creators.bilibili", "BilibiliCreatorProvider"),
    Platform.DOUYIN: ("getvideo.creators.douyin", "DouyinCreatorProvider"),
}


def get_provider(platform: Platform):
    try:
        module_name, class_name = _PROVIDERS[platform]
    except KeyError:
        raise CreatorUnavailable(f"暂不支持 {platform} 的博主批量") from None

    import importlib

    return getattr(importlib.import_module(module_name), class_name)()


__all__ = [
    "CreatorAuthError",
    "CreatorComment",
    "CreatorError",
    "CreatorNotFound",
    "CreatorProfile",
    "CreatorRangeError",
    "CreatorUnavailable",
    "CreatorVideo",
    "CreatorWindow",
    "VideoStats",
    "get_provider",
]
