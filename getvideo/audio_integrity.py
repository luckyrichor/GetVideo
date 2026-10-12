"""Repair legacy byte counts only when the stored hash verifies the file."""
import hashlib
from sqlalchemy import select
from .config import settings
from .db import session_scope, next_updated_seq
from .models import Track


def repair_legacy_audio_sizes() -> list[int]:
    repaired = []
    with session_scope() as session:
        for track in session.execute(select(Track).where(Track.deleted == 0)).scalars():
            path = settings.audio_dir / track.audio_path
            if not path.is_file() or not track.audio_sha256:
                continue
            before = path.stat()
            if before.st_size == track.audio_bytes:
                continue
            digest = hashlib.sha256()
            with path.open("rb") as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                    digest.update(chunk)
            after = path.stat()
            if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
                continue
            if digest.hexdigest() != track.audio_sha256.lower():
                continue
            track.audio_bytes = after.st_size
            track.updated_seq = next_updated_seq(session)
            session.flush()
            repaired.append(track.id)
    return repaired
