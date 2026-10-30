"""Chromaprint 根据视频声音生成相似候选，不能判定画面相似；不自动删除来源或视频。"""
from __future__ import annotations

import json
import logging
import struct
import subprocess
from pathlib import Path

from sqlalchemy import select

from .config import settings
from .db import session_scope
from .models import AudioFingerprint, Track

log = logging.getLogger(__name__)


def compute(path: Path) -> list[int] | None:
    try:
        result = subprocess.run(
            ["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-i", str(path),
             "-t", "120", "-vn", "-ac", "2", "-ar", "11025", "-f", "chromaprint",
             "-fp_format", "raw", "pipe:1"],
            capture_output=True, check=True, timeout=90,
        )
        # Chromaprint raw 输出为本机字节序的 32-bit 整数。
        if len(result.stdout) < 4 or len(result.stdout) % 4:
            return None
        return list(struct.unpack(f"={len(result.stdout) // 4}I", result.stdout))
    except (OSError, subprocess.SubprocessError):
        log.warning("无法生成声音指纹，保留正常提取结果")
        return None


def store(session, track: Track, values: list[int] | None) -> None:
    existing = session.get(AudioFingerprint, track.id)
    if values and track.duration_sec:
        if existing is None:
            existing = AudioFingerprint(track_id=track.id)
            session.add(existing)
        existing.values_json = json.dumps(values)
        existing.duration_sec = track.duration_sec
    elif existing is not None:
        session.delete(existing)


def similarity(left: list[int], right: list[int]) -> float:
    # 至少约 15 秒有效指纹；跳过单音、静音等低信息片段，避免误判。
    if min(len(left), len(right)) < 100 or min(len(set(left)), len(set(right))) < 20:
        return 0.0
    best = 0.0
    for offset in range(-8, 9):
        a, b = (left[offset:], right) if offset >= 0 else (left, right[-offset:])
        length = min(len(a), len(b))
        distance = sum((x ^ y).bit_count() for x, y in zip(a[:length], b[:length]))
        best = max(best, 1 - distance / (32 * length))
    return best


def duplicates(*, limit: int = 500, threshold: float = 0.95, owner_scope: str | None = None) -> list[dict]:
    """只返回长度接近、指纹相似的候选。最多扫描 limit 条，避免无界平方比较。"""
    with session_scope() as s:
        stmt = select(Track, AudioFingerprint).join(AudioFingerprint, Track.id == AudioFingerprint.track_id).where(Track.deleted == 0)
        if owner_scope is not None:
            stmt = stmt.where(Track.owner_scope == owner_scope)
        rows = list(s.execute(stmt.order_by(Track.id).limit(limit)))
    decoded = [(track, fp, json.loads(fp.values_json)) for track, fp in rows]
    found = []
    for i, (a, fa, va) in enumerate(decoded):
        for b, fb, vb in decoded[i + 1:]:
            if abs(fa.duration_sec - fb.duration_sec) > 2:
                continue
            score = similarity(va, vb)
            if score >= threshold:
                found.append({"track_ids": [a.id, b.id], "titles": [a.title, b.title],
                              "similarity": round(score, 4)})
    return found


def backfill(limit: int = 500, *, owner_scope: str | None = None) -> int:
    with session_scope() as s:
        stmt = select(Track).outerjoin(AudioFingerprint).where(Track.deleted == 0, AudioFingerprint.track_id.is_(None))
        if owner_scope is not None:
            stmt = stmt.where(Track.owner_scope == owner_scope)
        tracks = list(s.scalars(stmt.order_by(Track.id).limit(limit)))
    count = 0
    for track in tracks:
        values = compute(settings.audio_dir / track.audio_path)
        if values:
            with session_scope() as s:
                current = s.get(Track, track.id)
                if current and not current.deleted and current.updated_seq == track.updated_seq:
                    store(s, current, values)
                    count += 1
    return count
