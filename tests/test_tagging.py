"""使用真实视频验证各容器标签持久化，不依赖网络。"""
import subprocess

import pytest
from mutagen import File

from getvideo.extractors.base import MediaInfo
from getvideo.sharelink import Platform
from getvideo.tagging import write_tags


@pytest.mark.parametrize("extension,codec", [
    ("mp3", "libmp3lame"), ("flac", "flac"),
    ("ogg", "libopus"), ("m4a", "aac"),
])
def test_tags_roundtrip(tmp_path, extension, codec):
    path = tmp_path / f"sample.{extension}"
    subprocess.run([
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
        "-f", "lavfi", "-i", "sine=frequency=440:duration=0.1",
        "-c:a", codec, str(path),
    ], check=True)
    info = MediaInfo(
        platform=Platform.BILIBILI, video_id="test", title="中文标题",
        uploader="作者", webpage_url="https://www.bilibili.com/video/test",
        upload_date="20260927", tags=["钢琴", "轻音乐"],
    )
    assert write_tags(path, info)
    audio = File(str(path), easy=True)
    assert audio["title"] == ["中文标题"]
    assert audio["artist"] == ["作者"]
    assert audio["album"] == ["GetVideo-bilibili"]
    assert audio["date"] == ["2026"]
    assert audio["genre"] == ["钢琴, 轻音乐"]


def test_invalid_audio_is_nonfatal(tmp_path):
    path = tmp_path / "broken.mp3"
    path.write_bytes(b"not audio")
    info = MediaInfo(platform=Platform.BILIBILI, video_id="test", title="test", webpage_url="")
    assert write_tags(path, info) is False
