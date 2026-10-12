"""提取器接口。

每个平台一个实现，坏了只坏一个文件 —— 抖音的接口签名会变，
隔离在这一层能把爆炸半径压住。
"""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Protocol

from ..sharelink import Platform, ResolvedLink


@dataclass
class MediaInfo:
    """从平台拿到的视频元数据（还没下视频）。"""

    platform: Platform
    video_id: str
    title: str
    webpage_url: str
    uploader: str | None = None
    description: str | None = None
    thumbnail_url: str | None = None
    upload_date: str | None = None
    duration_sec: float | None = None
    tags: list[str] = field(default_factory=list)

    def text_for_classify(self) -> str:
        """喂给 P2 文本粗分的原料。"""
        parts = [self.title, self.uploader or "", self.description or "", " ".join(self.tags)]
        return "\n".join(p for p in parts if p).strip()


@dataclass
class ExtractResult:
    info: MediaInfo
    audio_path: Path
    audio_format: str
    audio_bytes: int


class ExtractorError(RuntimeError):
    """提取失败。message 要能直接给用户看。"""


class NeedsLoginError(ExtractorError):
    """要登录才能拿到这个资源（或这个音质）。"""


class Extractor(Protocol):
    platform: Platform

    def extract(self, link: ResolvedLink, *, cookie_file: Path | None = None) -> ExtractResult:
        ...
