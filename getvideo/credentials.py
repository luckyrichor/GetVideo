"""用户凭证的存与取。

存：AES-GCM 加密后写 platform_credentials。
取：解密后导出成 Netscape cookie 文件给提取器用 —— 两个提取器都
    认这个格式，不用为凭证再开一条通路。

cookie 文件写在 data/cookies/<user_id>__<platform>.txt，权限 600。
它是缓存，不是真相 —— 真相在库里，文件随时可以删掉重新导出。
"""
from __future__ import annotations

import logging
import time
from pathlib import Path

from sqlalchemy import select

from .config import settings
from .crypto import CryptoError, credential_aad, decrypt, encrypt
from .db import session_scope
from .models import PlatformCredential, utcnow

log = logging.getLogger(__name__)

STATUS_ACTIVE = "active"
STATUS_NEEDS_REAUTH = "needs_reauth"


def _cookie_path(user_id: str, platform: str) -> Path:
    return settings.cookie_dir / f"{user_id}__{platform}.txt"


def _domain_for(platform: str) -> str:
    return {"bilibili": ".bilibili.com", "douyin": ".douyin.com"}.get(platform, "")


def parse_cookie_header(text: str) -> dict[str, str]:
    """把浏览器里复制出来的 `a=1; b=2` 解析成字典。

    容忍换行、多余空格、以及用户不小心带上的 `Cookie:` 前缀。
    """
    text = (text or "").strip()
    if text.lower().startswith("cookie:"):
        text = text[len("cookie:"):]

    jar: dict[str, str] = {}
    for part in text.replace("\n", ";").split(";"):
        part = part.strip()
        if not part or "=" not in part:
            continue
        name, _, value = part.partition("=")
        name, value = name.strip(), value.strip()
        if name:
            jar[name] = value
    return jar


def save(user_id: str, platform: str, cookies: dict[str, str]) -> None:
    """加密存库，并立刻导出 cookie 文件。"""
    if not cookies:
        raise ValueError("没有可保存的 cookie")

    blob = encrypt(_to_header(cookies), aad=credential_aad(user_id, platform))

    with session_scope() as s:
        cred = s.execute(
            select(PlatformCredential).where(
                PlatformCredential.user_id == user_id,
                PlatformCredential.platform == platform,
            )
        ).scalar_one_or_none()
        if cred is None:
            cred = PlatformCredential(user_id=user_id, platform=platform)
            s.add(cred)
        cred.cookie_blob = blob
        cred.status = STATUS_ACTIVE
        cred.last_checked_at = utcnow()

    _export_cookie_file(user_id, platform, cookies)
    log.info("已保存 %s 的 %s 凭证（%d 项）", user_id, platform, len(cookies))


def load(user_id: str, platform: str) -> dict[str, str] | None:
    """解密取出。凭证不存在或已失效返回 None。"""
    with session_scope() as s:
        cred = s.execute(
            select(PlatformCredential).where(
                PlatformCredential.user_id == user_id,
                PlatformCredential.platform == platform,
                PlatformCredential.status == STATUS_ACTIVE,
            )
        ).scalar_one_or_none()
        if cred is None or not cred.cookie_blob:
            return None
        blob = cred.cookie_blob

    try:
        return parse_cookie_header(decrypt(blob, aad=credential_aad(user_id, platform)))
    except CryptoError as exc:
        # 解不开就等于没有 —— 但要吼一声，这通常意味着换了密钥
        log.error("解密 %s 的 %s 凭证失败：%s", user_id, platform, exc)
        return None


def ensure_cookie_file(user_id: str, platform: str) -> Path | None:
    """确保 cookie 文件存在且是最新的，返回路径。"""
    path = _cookie_path(user_id, platform)
    cookies = load(user_id, platform)
    if not cookies:
        path.unlink(missing_ok=True)
        return None
    return _export_cookie_file(user_id, platform, cookies)


def mark_needs_reauth(user_id: str, platform: str, reason: str = "") -> None:
    """标记凭证失效，同时删掉导出的 cookie 文件，避免继续被用。"""
    with session_scope() as s:
        cred = s.execute(
            select(PlatformCredential).where(
                PlatformCredential.user_id == user_id,
                PlatformCredential.platform == platform,
            )
        ).scalar_one_or_none()
        if cred:
            cred.status = STATUS_NEEDS_REAUTH
            cred.last_checked_at = utcnow()
    _cookie_path(user_id, platform).unlink(missing_ok=True)
    log.warning("已标记 %s 的 %s 凭证需要重新登录：%s", user_id, platform, reason or "未说明")


def revoke(user_id: str, platform: str) -> bool:
    """彻底删除凭证。用户主动登出时用。"""
    with session_scope() as s:
        cred = s.execute(
            select(PlatformCredential).where(
                PlatformCredential.user_id == user_id,
                PlatformCredential.platform == platform,
            )
        ).scalar_one_or_none()
        if cred is None:
            return False
        s.delete(cred)
    _cookie_path(user_id, platform).unlink(missing_ok=True)
    log.info("已删除 %s 的 %s 凭证", user_id, platform)
    return True


def list_for(user_id: str) -> list[dict]:
    """列出该用户的凭证状态。只回状态，不回凭证内容。"""
    with session_scope() as s:
        rows = s.execute(
            select(PlatformCredential).where(PlatformCredential.user_id == user_id)
        ).scalars().all()
        return [
            {
                "platform": c.platform,
                "status": c.status,
                "last_checked_at": c.last_checked_at.isoformat() if c.last_checked_at else None,
            }
            for c in rows
        ]


def _to_header(cookies: dict[str, str]) -> str:
    return "; ".join(f"{k}={v}" for k, v in cookies.items())


def _export_cookie_file(user_id: str, platform: str, cookies: dict[str, str]) -> Path:
    path = _cookie_path(user_id, platform)
    path.parent.mkdir(parents=True, exist_ok=True)
    domain = _domain_for(platform)
    expiry = int(time.time()) + 365 * 24 * 3600

    lines = ["# Netscape HTTP Cookie File", f"# GetVideo: {user_id} @ {platform}", ""]
    lines += [f"{domain}\tTRUE\t/\tFALSE\t{expiry}\t{k}\t{v}" for k, v in cookies.items()]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    path.chmod(0o600)
    return path
