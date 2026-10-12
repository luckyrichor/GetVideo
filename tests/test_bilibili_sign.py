"""WBI 签名的单测。用固定输入锁住算法，B 站改重排表时能立刻发现。"""
from __future__ import annotations

import pytest

from getvideo.extractors.bilibili import BilibiliExtractor, _key_from_url, _mixin_key, _sign
from getvideo.extractors.base import ExtractorError
from getvideo.sharelink import resolve


def test_key_from_url():
    assert _key_from_url("https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png") == (
        "7cd084941338484aae1ad9425b84077c"
    )


def test_mixin_key_is_32_chars():
    img = "7cd084941338484aae1ad9425b84077c"
    sub = "4932caff0ff746eab6f01bf08b70ac45"
    key = _mixin_key(img, sub)
    assert len(key) == 32
    assert set(key) <= set(img + sub)


def test_sign_adds_wts_and_wrid():
    signed = _sign({"bvid": "BV1GJ411x7h7", "cid": 137649199}, "0" * 32)
    assert "wts" in signed and "w_rid" in signed
    assert len(signed["w_rid"]) == 32  # md5 hex
    # 原参数不能被改动
    assert signed["bvid"] == "BV1GJ411x7h7"


def test_sign_is_deterministic_for_same_wts():
    key = "a" * 32
    a = _sign({"x": 1}, key)
    b = dict(a)
    del b["w_rid"]
    # 用同样的 wts 重算，结果必须一致
    import hashlib, urllib.parse
    q = "&".join(f"{k}={urllib.parse.quote(str(b[k]), safe='')}" for k in sorted(b))
    assert hashlib.md5((q + key).encode()).hexdigest() == a["w_rid"]


def test_fetch_view_selects_requested_part():
    class Client:
        def get(self, *_args, **_kwargs):
            class Response:
                def json(self):
                    return {"code": 0, "data": {"bvid": "BV1GJ411x7h7", "title": "合集",
                            "pages": [{"cid": 101, "part": "一"}, {"cid": 202, "part": "二"}]}}
            return Response()

    extractor = BilibiliExtractor()
    link = resolve("https://www.bilibili.com/video/BV1GJ411x7h7?p=2")
    info, cid = extractor._fetch_view(Client(), link)
    assert cid == 202
    assert info["title"] == "合集 - P2 二"
    media = extractor._to_media_info(info, link)
    assert media.video_id == link.track_key
    assert media.webpage_url.endswith("?p=2")

    missing = resolve("https://www.bilibili.com/video/BV1GJ411x7h7?p=3")
    with pytest.raises(ExtractorError, match="没有 P3"):
        extractor._fetch_view(Client(), missing)
