"""抖音登录。

**扫码目前走不通**：`passport/web/get_qrcode/` 在服务器上返回
`error_code 4031`（"存在安全风险，已阻止此次访问"）。抖音的登录
接口要求完整的 `a_bogus` / `msToken` 签名，这套签名是重度混淆的
JS，靠逆向跟随的维护成本极高，而且每次平台更新都会断。

所以抖音走**凭证导入**：用户在自己浏览器里登录抖音，把 cookie 导出
贴进来。效果与扫码等价 —— 拿到的是该用户本人的凭证，同样按用户
隔离、同样加密存库。

扫码的实现保留在下面，平台哪天放开了可以直接启用。
"""
from __future__ import annotations

import logging

import httpx

from ..config import settings
from .base import LoginError, LoginNotSupported, QrPollResult, QrState, QrTicket

log = logging.getLogger(__name__)

_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)

API_QRCODE = "https://www.douyin.com/passport/web/get_qrcode/"
API_QR_CHECK = "https://www.douyin.com/passport/web/check_qrconnect/"

# 值得保留的凭证字段。sessionid 系列是登录态的核心。
_WANTED = (
    "sessionid", "sessionid_ss", "sid_tt", "uid_tt", "uid_tt_ss",
    "sid_guard", "ttwid", "passport_csrf_token", "odin_tt",
)

_HOW_TO_EXPORT = (
    "在浏览器里登录抖音 → F12 打开开发者工具 → Application/存储 → Cookies → "
    "复制 douyin.com 下的全部 cookie（形如 `sessionid=xxx; ttwid=yyy`）"
)


class DouyinLogin:
    platform = "douyin"

    def start(self) -> QrTicket:
        with httpx.Client(
            timeout=settings.http_timeout,
            headers={"User-Agent": _UA, "Referer": "https://www.douyin.com/"},
            follow_redirects=True,
        ) as client:
            try:
                data = client.get(
                    API_QRCODE,
                    params={"service": "https://www.douyin.com", "aid": "6383", "need_logo": "false"},
                ).json()
            except Exception as exc:
                raise LoginNotSupported(
                    f"抖音扫码接口不可用（{exc}）。请改用凭证导入：{_HOW_TO_EXPORT}"
                ) from exc

        d = data.get("data") or {}
        error_code = d.get("error_code")
        if error_code or not d.get("qrcode"):
            desc = d.get("description") or data.get("message") or "未知原因"
            raise LoginNotSupported(
                f"抖音拒绝了扫码请求（{desc}）。请改用凭证导入：{_HOW_TO_EXPORT}"
            )

        return QrTicket(
            platform=self.platform,
            key=d.get("token", ""),
            qr_url=d.get("qrcode_index_url") or d["qrcode"],
            expires_in=180,
        )

    def poll(self, key: str) -> QrPollResult:
        with httpx.Client(
            timeout=settings.http_timeout,
            headers={"User-Agent": _UA, "Referer": "https://www.douyin.com/"},
            follow_redirects=True,
        ) as client:
            try:
                resp = client.get(
                    API_QR_CHECK,
                    params={"service": "https://www.douyin.com", "aid": "6383", "token": key},
                )
                data = resp.json()
            except Exception as exc:
                raise LoginError(f"轮询失败：{exc}") from exc

            d = data.get("data") or {}
            status = d.get("status")
            if status == "confirmed":
                cookies = {k: v for k, v in dict(client.cookies).items() if k in _WANTED}
                if "sessionid" not in cookies:
                    return QrPollResult(QrState.FAILED, "确认成功但没拿到 sessionid")
                return QrPollResult(QrState.CONFIRMED, "登录成功", cookies)
            if status == "scanned":
                return QrPollResult(QrState.SCANNED, "已扫码，请在手机上确认")
            if status == "expired":
                return QrPollResult(QrState.EXPIRED, "二维码已失效")
            if status == "new":
                return QrPollResult(QrState.WAITING, "等待扫码")
            return QrPollResult(QrState.FAILED, d.get("description") or f"未知状态：{status}")


def how_to_export() -> str:
    return _HOW_TO_EXPORT
