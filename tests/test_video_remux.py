"""Exercise real FFmpeg, checking video frames and soundtrack preservation."""
import subprocess
from pathlib import Path
import pytest
from getvideo.extractors.video import remux_video, probe_video
from getvideo.extractors.base import ExtractorError
from getvideo.extractors.bilibili import select_dash_streams


def run(*args):
    subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-y", *map(str, args)], check=True)


def video(path):
    run("-f", "lavfi", "-i", "testsrc2=size=128x96:rate=15:duration=1", "-c:v", "libx264", "-pix_fmt", "yuv420p", path)


def audio(path):
    run("-f", "lavfi", "-i", "sine=frequency=440:duration=1", "-c:a", "aac", path)


def stream_hash(path, kind):
    return subprocess.check_output(["ffmpeg", "-v", "error", "-i", str(path), "-map", f"0:{kind}:0", "-c", "copy", "-f", "hash", "-hash", "sha256", "-"])


def test_dash_merge_preserves_both_streams(tmp_path):
    v, a = tmp_path / "video.mp4", tmp_path / "sound.m4a"
    video(v); audio(a)
    out = remux_video(v, tmp_path / "title.with.dots", a)
    assert out.name == "title.with.dots.mp4"
    info = probe_video(out)
    assert {s["codec_type"] for s in info["streams"]} == {"video", "audio"}
    assert stream_hash(v, "v") == stream_hash(out, "v")
    assert stream_hash(a, "a") == stream_hash(out, "a")
    assert 0.9 <= float(info["format"]["duration"]) <= 1.2


def test_combined_and_silent_videos(tmp_path):
    v, a = tmp_path / "v.mp4", tmp_path / "a.m4a"
    video(v); audio(a)
    combined = remux_video(v, tmp_path / "combined", a)
    out = remux_video(combined, tmp_path / "saved")
    assert stream_hash(out, "v") == stream_hash(combined, "v")
    assert stream_hash(out, "a") == stream_hash(combined, "a")
    silent = remux_video(v, tmp_path / "silent")
    assert [s["codec_type"] for s in probe_video(silent)["streams"]] == ["video"]


def test_music_only_rejected(tmp_path):
    a = tmp_path / "music.m4a"; audio(a)
    with pytest.raises(ExtractorError, match="没有视频画面"):
        remux_video(a, tmp_path / "wrong")
    assert not (tmp_path / "wrong.mp4").exists()


def test_dash_chooses_best_avc_and_sound():
    streams = {"video": [{"id": 120, "codecid": 12, "baseUrl": "hevc"},
                         {"id": 64, "codecid": 7, "baseUrl": "720p"},
                         {"id": 80, "codecid": 7, "baseUrl": "1080p"}],
               "audio": [{"bandwidth": 64, "baseUrl": "a1"}, {"bandwidth": 192, "baseUrl": "a2"}]}
    v, a = select_dash_streams(streams)
    assert v["baseUrl"] == "1080p" and a["baseUrl"] == "a2"


def test_dash_supports_silent_video_but_rejects_audio_only():
    assert select_dash_streams({"video": [{"base_url": "silent"}]})[1] is None
    with pytest.raises(ExtractorError, match="视频画面"):
        select_dash_streams({"audio": [{"baseUrl": "music"}]})
