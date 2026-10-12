"""B 站扫码登录。

官方 Web 扫码流程，三步：

    generate → 拿 qrcode_key 和二维码内容
    用户用 B 站 App 扫
    poll     → 轮询状态，确认后 Set-Cookie 带回 SESSDATA 等凭证

轮询返回的 data.code 有四种：
    0     确认成功
    86090 已扫码，等待手机端确认
    86101 未扫码
    86038 二维码已失效
"""
from __future__ import annotations

import logging

import httpx

from ..config import settings
from .base import LoginError, QrPollResult, QrState, QrTicket

log = logging.getLogger(__name__)

_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)

API_GENERATE = "https://passport.bilibili.com/x/passport-login/web/qrcode/generate"
API_POLL = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll"

_STATE_BY_CODE = {
    0: QrState.CONFIRMED,
    86090: QrState.SCANNED,
    86101: QrState.WAITING,
    86038: QrState.EXPIRED,
}

# 真正有用的那几个 cookie。其余的不存，减少泄漏面。
_WANTED = ("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "sid", "buvid3")


def _client() -> httpx.Client:
    return httpx.Client(
        timeout=settings.http_timeout,
        headers={"User-Agent": _UA, "Referer": "https://www.bilibili.com/"},
        follow_redirects=True,
    )


class BilibiliLogin:
    platform = "bilibili"

    def start(self) -> QrTicket:
        with _client() as client:
            try:
                data = client.get(API_GENERATE).json()
            except Exception as exc:
                raise LoginError(f"申请二维码失败：{exc}") from exc

        if data.get("code") != 0:
            raise LoginError(f"申请二维码失败：{data.get('message')}")

        d = data.get("data") or {}
        if not d.get("qrcode_key") or not d.get("url"):
            raise LoginError("二维码接口返回内容不完整")

        return QrTicket(platform=self.platform, key=d["qrcode_key"], qr_url=d["url"], expires_in=180)

    def poll(self, key: str) -> QrPollResult:
        with _client() as client:
            try:
                resp = client.get(API_POLL, params={"qrcode_key": key})
                data = resp.json()
            except Exception as exc:
                raise LoginError(f"轮询失败：{exc}") from exc

            if data.get("code") != 0:
                return QrPollResult(QrState.FAILED, str(data.get("message") or "轮询接口报错"))

            inner = data.get("data") or {}
            code = inner.get("code")
            state = _STATE_BY_CODE.get(code, QrState.FAILED)
            message = inner.get("message") or ""

            if state is not QrState.CONFIRMED:
                return QrPollResult(state, message)

            # 确认成功：凭证在响应的 Set-Cookie 里
            cookies = {k: v for k, v in resp.cookies.items() if k in _WANTED}
            if not cookies:
                # 极少数情况 cookie 挂在 client 上而非 response
                cookies = {k: v for k, v in dict(client.cookies).items() if k in _WANTED}
            if "SESSDATA" not in cookies:
                return QrPollResult(QrState.FAILED, "确认成功但没拿到 SESSDATA，请重试")

            log.info("B 站扫码登录成功，拿到 cookie: %s", ", ".join(sorted(cookies)))
            return QrPollResult(QrState.CONFIRMED, "登录成功", cookies)
