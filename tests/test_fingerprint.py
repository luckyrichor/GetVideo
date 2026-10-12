import random
import subprocess

from getvideo.fingerprint import compute, similarity


def test_similarity_handles_shift_and_different_audio():
    rng = random.Random(42)
    a = [rng.getrandbits(32) for _ in range(150)]
    b = [rng.getrandbits(32) for _ in range(150)]
    assert similarity(a, a) == 1.0
    assert similarity(a, a[4:]) == 1.0
    assert similarity(a, b) < 0.8
    assert similarity([0] * 150, [0] * 150) == 0.0
    assert similarity([], []) == 0.0


def test_real_fingerprint_ignores_metadata(tmp_path):
    path = tmp_path / "sample.flac"
    subprocess.run(["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                    "-f", "lavfi", "-i", "anoisesrc=d=20:seed=42", str(path)], check=True)
    before = compute(path)
    assert before and len(before) >= 100
    from mutagen.flac import FLAC
    audio = FLAC(path)
    audio["title"] = ["changed title"]
    audio.save()
    assert compute(path) == before


def test_missing_file_is_nonfatal(tmp_path):
    assert compute(tmp_path / "missing") is None
