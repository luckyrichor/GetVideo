"""凭证加密存取的单测。

安全相关的代码尤其需要用例钉住 —— 一次无意的重构把加密退化成明文
存储，功能上完全看不出来。
"""
from __future__ import annotations

import pytest

from getvideo import credentials as cr
from getvideo.crypto import CryptoError, credential_aad, decrypt, encrypt
from getvideo.db import init_db, session_scope
from getvideo.models import PlatformCredential


@pytest.fixture(autouse=True)
def _clean():
    init_db()
    with session_scope() as s:
        s.query(PlatformCredential).filter(PlatformCredential.user_id.like("test_%")).delete(
            synchronize_session=False
        )
    yield
    with session_scope() as s:
        s.query(PlatformCredential).filter(PlatformCredential.user_id.like("test_%")).delete(
            synchronize_session=False
        )


# ---------- 加密本身 ----------


def test_roundtrip():
    aad = credential_aad("test_u", "bilibili")
    assert decrypt(encrypt("SESSDATA=abc", aad=aad), aad=aad) == "SESSDATA=abc"


def test_aad_binding_blocks_cross_user_reuse():
    """把 A 的密文挪到 B 名下必须解不开 —— 防数据库被改写后串用。"""
    blob = encrypt("SESSDATA=abc", aad=credential_aad("test_a", "bilibili"))
    with pytest.raises(CryptoError):
        decrypt(blob, aad=credential_aad("test_b", "bilibili"))


def test_tampered_ciphertext_is_rejected():
    """GCM 自带完整性校验 —— 改一个字节就该报错，而不是解出垃圾。"""
    aad = credential_aad("test_u", "bilibili")
    blob = bytearray(encrypt("SESSDATA=abc", aad=aad))
    blob[-1] ^= 0xFF
    with pytest.raises(CryptoError):
        decrypt(bytes(blob), aad=aad)


def test_nonce_differs_each_time():
    """相同明文不能产生相同密文，否则能看出两个用户凭证是否一样。"""
    aad = credential_aad("test_u", "bilibili")
    assert encrypt("same", aad=aad) != encrypt("same", aad=aad)


# ---------- cookie 解析 ----------


@pytest.mark.parametrize(
    "text,expected",
    [
        ("a=1; b=2", {"a": "1", "b": "2"}),
        ("Cookie: a=1; b=2", {"a": "1", "b": "2"}),
        ("  a=1 ;  b=2  ", {"a": "1", "b": "2"}),
        ("a=1\nb=2", {"a": "1", "b": "2"}),
        ("a=1; 垃圾; b=2", {"a": "1", "b": "2"}),
        # 值里带等号（base64 padding 很常见）不能被截断
        ("a=eyJ4Ijox=; b=2", {"a": "eyJ4Ijox=", "b": "2"}),
        ("", {}),
    ],
)
def test_parse_cookie_header(text, expected):
    assert cr.parse_cookie_header(text) == expected


# ---------- 存取 ----------


def test_save_stores_ciphertext_not_plaintext():
    """最关键的一条：库里不能出现明文。"""
    cr.save("test_u", "bilibili", {"SESSDATA": "supersecret"})
    with session_scope() as s:
        row = s.query(PlatformCredential).filter_by(user_id="test_u").one()
        assert b"supersecret" not in row.cookie_blob
        assert b"SESSDATA" not in row.cookie_blob


def test_save_then_load():
    cookies = {"SESSDATA": "abc", "bili_jct": "def"}
    cr.save("test_u", "bilibili", cookies)
    assert cr.load("test_u", "bilibili") == cookies


def test_cookie_file_is_netscape_and_private():
    cr.save("test_u", "bilibili", {"SESSDATA": "abc"})
    path = cr.ensure_cookie_file("test_u", "bilibili")
    assert path and path.exists()
    assert path.read_text().startswith("# Netscape HTTP Cookie File")
    assert "SESSDATA\tabc" in path.read_text()
    # 凭证文件不能让同机其他用户读到
    assert oct(path.stat().st_mode)[-3:] == "600"


def test_cookie_file_is_readable_by_extractor():
    """导出的文件必须能被提取器那边的解析函数读回来 —— 两边格式要一致。"""
    from getvideo.extractors.bilibili import _parse_cookie_file

    cr.save("test_u", "bilibili", {"SESSDATA": "abc", "bili_jct": "def"})
    path = cr.ensure_cookie_file("test_u", "bilibili")
    assert _parse_cookie_file(path) == {"SESSDATA": "abc", "bili_jct": "def"}


def test_mark_needs_reauth_disables_credential():
    cr.save("test_u", "bilibili", {"SESSDATA": "abc"})
    cr.mark_needs_reauth("test_u", "bilibili", "测试")
    assert cr.load("test_u", "bilibili") is None
    # cookie 文件也要删掉，不能继续被拿去用
    assert cr.ensure_cookie_file("test_u", "bilibili") is None
    assert cr.list_for("test_u")[0]["status"] == "needs_reauth"


def test_save_again_reactivates():
    """重新登录应该复活同一条记录，而不是新增一条。"""
    cr.save("test_u", "bilibili", {"SESSDATA": "old"})
    cr.mark_needs_reauth("test_u", "bilibili")
    cr.save("test_u", "bilibili", {"SESSDATA": "new"})
    assert cr.load("test_u", "bilibili") == {"SESSDATA": "new"}
    assert len(cr.list_for("test_u")) == 1


def test_users_are_isolated():
    cr.save("test_a", "bilibili", {"SESSDATA": "aaa"})
    cr.save("test_b", "bilibili", {"SESSDATA": "bbb"})
    assert cr.load("test_a", "bilibili") == {"SESSDATA": "aaa"}
    assert cr.load("test_b", "bilibili") == {"SESSDATA": "bbb"}


def test_revoke_removes_everything():
    cr.save("test_u", "bilibili", {"SESSDATA": "abc"})
    assert cr.revoke("test_u", "bilibili") is True
    assert cr.load("test_u", "bilibili") is None
    assert cr.list_for("test_u") == []
    assert cr.revoke("test_u", "bilibili") is False


def test_list_never_leaks_cookie_content():
    cr.save("test_u", "bilibili", {"SESSDATA": "supersecret"})
    assert "supersecret" not in str(cr.list_for("test_u"))
