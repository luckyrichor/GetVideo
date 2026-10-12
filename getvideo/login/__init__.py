"""扫码登录的入口与会话管理。

会话存在内存里：二维码只活三分钟，重启服务时丢掉未完成的会话没有
任何损失 —— 用户重新点一下就行。真正要持久化的是登录成功后的凭证，
那部分在 credentials 模块。
"""
from __future__ import annotations

import logging
import secrets
import threading
import time
from dataclasses import dataclass

from .. import credentials
from .base import LoginError, LoginNotSupported, QrPollResult, QrState, QrTicket
from .bilibili import BilibiliLogin
from .douyin import DouyinLogin, how_to_export

log = logging.getLogger(__name__)

_PROVIDERS = {
    "bilibili": BilibiliLogin(),
    "douyin": DouyinLogin(),
}

# 会话保留到二维码过期后再多留一会儿，好让前端读到终态
_SESSION_TTL = 600


@dataclass
class QrSession:
    session_id: str
    user_id: str
    platform: str
    key: str
    qr_url: str
    created_at: float
    expires_in: int
    state: QrState = QrState.WAITING
    message: str = ""


_sessions: dict[str, QrSession] = {}
_lock = threading.RLock()


def supported_platforms() -> list[str]:
    return sorted(_PROVIDERS)


def _provider(platform: str):
    try:
        return _PROVIDERS[platform]
    except KeyError:
        raise LoginError(f"不支持的平台：{platform}") from None


def _gc() -> None:
    now = time.time()
    for sid in [s for s, v in _sessions.items() if now - v.created_at > _SESSION_TTL]:
        _sessions.pop(sid, None)


def start(user_id: str, platform: str) -> QrSession:
    ticket: QrTicket = _provider(platform).start()
    session = QrSession(
        session_id=secrets.token_urlsafe(16),
        user_id=user_id,
        platform=platform,
        key=ticket.key,
        qr_url=ticket.qr_url,
        created_at=time.time(),
        expires_in=ticket.expires_in,
    )
    with _lock:
        _gc()
        _sessions[session.session_id] = session
    log.info("为 %s 开了一个 %s 扫码会话", user_id, platform)
    return session


def peek(session_id: str, owner: str | None = None) -> QrSession:
    with _lock:
        session = _sessions.get(session_id)
        if session is None or (owner is not None and session.user_id != owner) or (owner is None and session.user_id.startswith("acct_")):
            raise LoginError("授权会话不存在或已过期")
        return session


def cancel(user_id: str, platform: str):
    with _lock:
        for q in _sessions.values():
            if q.user_id == user_id and q.platform == platform:
                q.state = QrState.FAILED
                q.message = "授权已解除"


def poll(session_id: str, owner: str | None = None) -> QrSession:
    # Serialize polling with revocation so an in-flight result cannot restore revoked cookies.
    with _lock:
        return _poll(session_id, owner)


def _poll(session_id: str, owner: str | None = None) -> QrSession:
    session = peek(session_id, owner)
    if session is None:
        raise LoginError("扫码会话不存在或已过期，请重新发起")

    # 终态不再打平台接口
    if session.state in (QrState.CONFIRMED, QrState.EXPIRED, QrState.FAILED):
        return session

    if time.time() - session.created_at > session.expires_in:
        session.state, session.message = QrState.EXPIRED, "二维码已超时，请重新发起"
        return session

    result: QrPollResult = _provider(session.platform).poll(session.key)
    session.state, session.message = result.state, result.message

    if result.state is QrState.CONFIRMED and result.cookies:
        # 拿到凭证立刻加密落库，不在内存里多留
        credentials.save(session.user_id, session.platform, result.cookies)
        session.message = "登录成功，凭证已保存"

    return session


def import_cookies(user_id: str, platform: str, cookie_text: str) -> dict:
    """凭证导入。抖音当前只能走这条路。"""
    _provider(platform)  # 校验平台名
    cookies = credentials.parse_cookie_header(cookie_text)
    if not cookies:
        raise LoginError("没有解析出任何 cookie，请检查粘贴的内容")

    required = {"bilibili": "SESSDATA", "douyin": "sessionid"}.get(platform)
    if required and required not in cookies:
        raise LoginError(f"缺少关键字段 {required} —— 这份 cookie 可能不是登录态")

    credentials.save(user_id, platform, cookies)
    return {"platform": platform, "saved_keys": sorted(cookies)}


__all__ = [
    "LoginError",
    "LoginNotSupported",
    "QrSession",
    "QrState",
    "how_to_export",
    "import_cookies",
    "poll",
    "start",
    "supported_platforms",
]
