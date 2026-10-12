"""凭证加密。

登录 cookie 等同于账号本身 —— 明文存库意味着一次数据库泄漏就是
一批账号失守。这里用 AES-GCM 加密，密钥只从环境变量读，绝不落盘、
绝不进代码库。

GCM 而不是 CBC：它自带完整性校验，密文被改动会在解密时直接报错，
而不是悄悄解出一段垃圾。
"""
from __future__ import annotations

import base64
import logging
import os
from pathlib import Path

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from .config import ROOT

log = logging.getLogger(__name__)

_ENV_KEY = "GETVIDEO_SECRET_KEY"
# 开发环境的密钥落在这个文件里（已在 .gitignore）。生产必须走环境变量。
_KEY_FILE = ROOT / "data" / ".secret_key"

_NONCE_BYTES = 12  # GCM 标准 nonce 长度
_KEY_BYTES = 32    # AES-256


class CryptoError(RuntimeError):
    pass


def _load_key() -> bytes:
    if raw := os.environ.get(_ENV_KEY):
        try:
            key = base64.urlsafe_b64decode(raw)
        except Exception as exc:
            raise CryptoError(f"{_ENV_KEY} 不是合法的 base64：{exc}") from exc
        if len(key) != _KEY_BYTES:
            raise CryptoError(f"{_ENV_KEY} 解码后应为 {_KEY_BYTES} 字节，实际 {len(key)}")
        return key

    # 本地开发：没配环境变量就自动生成一把，权限 600
    if _KEY_FILE.exists():
        return base64.urlsafe_b64decode(_KEY_FILE.read_text().strip())

    log.warning(
        "未设置 %s，已在 %s 自动生成开发用密钥。生产环境请改用环境变量。",
        _ENV_KEY, _KEY_FILE,
    )
    key = AESGCM.generate_key(bit_length=256)
    _KEY_FILE.parent.mkdir(parents=True, exist_ok=True)
    _KEY_FILE.write_text(base64.urlsafe_b64encode(key).decode())
    _KEY_FILE.chmod(0o600)
    return key


_key_cache: bytes | None = None


def _key() -> bytes:
    global _key_cache
    if _key_cache is None:
        _key_cache = _load_key()
    return _key_cache


def encrypt(plaintext: str, *, aad: str | None = None) -> bytes:
    """加密。

    aad（附加认证数据）传 "<user_id>:<platform>"，这样一条凭证的密文
    被挪到另一个用户名下时解密会失败 —— 防的是数据库被改写后的串用。
    """
    nonce = os.urandom(_NONCE_BYTES)
    ct = AESGCM(_key()).encrypt(nonce, plaintext.encode(), aad.encode() if aad else None)
    return nonce + ct


def decrypt(blob: bytes, *, aad: str | None = None) -> str:
    if len(blob) <= _NONCE_BYTES:
        raise CryptoError("密文过短，数据已损坏")
    nonce, ct = blob[:_NONCE_BYTES], blob[_NONCE_BYTES:]
    try:
        return AESGCM(_key()).decrypt(nonce, ct, aad.encode() if aad else None).decode()
    except InvalidTag as exc:
        raise CryptoError("解密失败：密钥不匹配，或密文已被篡改") from exc


def credential_aad(user_id: str, platform: str) -> str:
    return f"{user_id}:{platform}"
