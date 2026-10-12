"""按平台路由到具体提取器。"""
from __future__ import annotations

from ..sharelink import Platform
from .base import Extractor, ExtractorError, ExtractResult, MediaInfo, NeedsLoginError
from .bilibili import BilibiliExtractor
from .douyin import DouyinExtractor
from .ytdlp import YtDlpExtractor

_REGISTRY: dict[Platform, Extractor] = {
    # B 站走原生 API：视频页在机房 IP 上会被风控回 412，yt-dlp 过不去
    Platform.BILIBILI: BilibiliExtractor(),
    # 抖音同样走原生 API：yt-dlp 在作品不可见时会统一报「需要 cookie」，
    # 掩盖了真实原因（已删除 / 仅好友可见 / 审核中）
    Platform.DOUYIN: DouyinExtractor(),
}


def get_extractor(platform: Platform) -> Extractor:
    try:
        return _REGISTRY[platform]
    except KeyError:
        raise ExtractorError(f"没有 {platform} 的提取器") from None


__all__ = [
    "Extractor",
    "ExtractorError",
    "ExtractResult",
    "MediaInfo",
    "NeedsLoginError",
    "get_extractor",
]
