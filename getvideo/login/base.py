"""扫码登录的共用类型。"""
from __future__ import annotations

import enum
from dataclasses import dataclass, field


class QrState(str, enum.Enum):
    WAITING = "waiting"      # 二维码已生成，等用户扫
    SCANNED = "scanned"      # 已扫，等用户在手机上确认
    CONFIRMED = "confirmed"  # 确认完成，凭证已拿到
    EXPIRED = "expired"      # 二维码超时
    FAILED = "failed"        # 平台侧报错


@dataclass
class QrTicket:
    """一次扫码会话。"""

    platform: str
    key: str            # 平台侧的会话标识，轮询时用
    qr_url: str         # 要渲染成二维码图片的内容
    expires_in: int = 180


@dataclass
class QrPollResult:
    state: QrState
    message: str = ""
    cookies: dict[str, str] = field(default_factory=dict)


class LoginError(RuntimeError):
    """登录流程失败。message 要能直接给用户看。"""


class LoginNotSupported(LoginError):
    """该平台当前无法走扫码（比如被风控挡住）。"""
