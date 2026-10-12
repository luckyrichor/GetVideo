import hashlib
from fastapi.testclient import TestClient
from getvideo.api import app
from getvideo.audio_integrity import repair_legacy_audio_sizes
from getvideo.config import settings
from getvideo.db import init_db, session_scope, next_updated_seq
from getvideo.models import Track


def make_track(name, data, recorded_bytes, stored_hash=None):
    init_db()
    (settings.audio_dir / name).write_bytes(data)
    with session_scope() as s:
        t = Track(platform="bilibili", video_id=name, title=name, source_url="",
                  audio_path=name, audio_format="mp3", audio_bytes=recorded_bytes,
                  audio_sha256=stored_hash or hashlib.sha256(data).hexdigest(), updated_seq=next_updated_seq(s))
        s.add(t); s.flush()
        return t.id, t.updated_seq


def test_size_repair_advances_cursor_and_matches_download():
    data = b"audio-with-tags"
    track_id, cursor = make_track("legacy-size.mp3", data, len(data) - 5)
    assert track_id in repair_legacy_audio_sizes()
    with TestClient(app) as client:
        page = client.get("/sync", params={"cursor": cursor}).json()
        row = next(t for t in page["items"] if t["id"] == track_id)
        downloaded = client.get(row["download_url"]).content
        assert row["audio_bytes"] == len(downloaded) == len(data)
        assert row["audio_sha256"] == hashlib.sha256(downloaded).hexdigest()
        assert row["updated_seq"] > cursor
    assert track_id not in repair_legacy_audio_sizes()


def test_hash_mismatch_is_not_blessed_as_new_content():
    track_id, cursor = make_track("corrupt-size.mp3", b"corrupt", 4, "0" * 64)
    assert track_id not in repair_legacy_audio_sizes()
    with session_scope() as s:
        t = s.get(Track, track_id)
        assert t.audio_bytes == 4
        assert t.audio_sha256 == "0" * 64
        assert t.updated_seq == cursor
