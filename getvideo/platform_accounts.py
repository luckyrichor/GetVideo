"""Phone-owned platform identities. Provider proofs never create phone accounts."""
from __future__ import annotations

import hmac
import json
import os
import secrets
from datetime import timedelta, timezone
from io import BytesIO
from typing import Literal
from urllib.parse import urlencode, urlparse

import httpx
import qrcode
from fastapi import APIRouter, Depends, Header, HTTPException, Query
from fastapi.responses import HTMLResponse, Response
from pydantic import BaseModel, ConfigDict, Field, SecretStr
from sqlalchemy import delete, select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from .accounts import authenticated_user, current_user, db, limit_auth, new_session, password_matches, token_hash
from .config import settings
from .crypto import credential_aad, decrypt, encrypt
from .login.bilibili import BilibiliLogin
from .login.base import QrState, LoginError
from .models import AccountPlatformAttempt as Attempt, AccountPlatformBinding as Binding, AppUser, PlatformCredential, utcnow

router = APIRouter(prefix="/account", tags=["platform account login"])


class StartBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    platform: Literal["bilibili", "douyin"]
    mode: Literal["bind", "login"]


class ProofBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    attempt_id: str = Field(min_length=32, max_length=128)
    poll_token: SecretStr = Field(min_length=32, max_length=128)


def douyin_ready() -> bool:
    url = urlparse(settings.douyin_redirect_uri)
    return bool(settings.douyin_client_key and settings.douyin_client_secret and url.scheme == "https" and
                url.netloc and not url.username and not url.query and not url.fragment and
                url.path.endswith("/account/platform-auth/douyin/callback"))


@router.get("/platforms")
def platforms():
    return [
        {"platform": "bilibili", "available": True, "method": "qr", "note": "使用 B站 App 扫码确认；首次需手机号注册后绑定"},
        {"platform": "douyin", "available": douyin_ready(), "method": "oauth", "note": "抖音开放平台授权" if douyin_ready() else "抖音授权暂未配置，请使用手机号登录"},
    ]


@router.get("/platform-bindings")
def bindings(user: AppUser = Depends(current_user), session: Session = Depends(db)):
    return [{"platform": row.platform, "nickname": row.nickname} for row in session.scalars(select(Binding).where(Binding.user_id == user.id))]


@router.post("/platform-auth/start", dependencies=[Depends(limit_auth)])
def start(body: StartBody, x_getvideo_session: str | None = Header(None), session: Session = Depends(db)):
    user = authenticated_user(x_getvideo_session, session) if body.mode == "bind" else None
    if body.platform == "douyin" and not douyin_ready():
        raise HTTPException(503, "抖音授权暂未配置，请使用手机号登录")
    state, poll = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
    state_digest = token_hash(state)
    expires = 180 if body.platform == "bilibili" else 600
    key, qr_url, auth_url = None, None, None
    if body.platform == "bilibili":
        try:
            ticket = BilibiliLogin().start()
        except LoginError:
            raise HTTPException(502, "B站二维码获取失败，请稍后重试") from None
        key, qr_url = ticket.key, ticket.qr_url
        expires = min(ticket.expires_in, 180)
    else:
        auth_url = "https://open.douyin.com/platform/oauth/connect?" + urlencode({
            "client_key": settings.douyin_client_key, "response_type": "code", "scope": "user_info",
            "redirect_uri": settings.douyin_redirect_uri, "state": state, "is_call_app": "1",
        })
    session.execute(delete(Attempt).where(Attempt.expires_at < utcnow()))
    session.add(Attempt(state_hash=state_digest, poll_hash=token_hash(poll), platform=body.platform, mode=body.mode,
        user_id=user.id if user else None, owner_session_hash=token_hash(x_getvideo_session) if user else None,
        key_blob=encrypt(json.dumps({"key": key, "qr_url": qr_url}), aad=state_digest) if key else None,
        expires_at=utcnow() + timedelta(seconds=expires)))
    session.commit()
    return {"attempt_id": state, "poll_token": poll, "platform": body.platform,
            "method": "qr" if key else "oauth", "qr_url": qr_url, "authorization_url": auth_url, "expires_in": expires}


def authorized_attempt(body: ProofBody, session: Session, account_token: str | None) -> Attempt:
    row = session.get(Attempt, token_hash(body.attempt_id))
    if row is None or not hmac.compare_digest(row.poll_hash, token_hash(body.poll_token.get_secret_value())):
        raise HTTPException(404, "授权会话不存在")
    if row.expires_at.replace(tzinfo=timezone.utc) <= utcnow():
        raise HTTPException(410, "授权已过期，请重新发起")
    if row.mode == "bind":
        user = authenticated_user(account_token, session)
        if user.id != row.user_id or token_hash(account_token) != row.owner_session_hash:
            raise HTTPException(403, "账号已切换，请重新发起绑定")
    return row


def verify_bilibili(cookies: dict[str, str]) -> tuple[str, str]:
    # The cookie's DedeUserID is NOT an identity proof; verify it on the provider.
    with httpx.Client(timeout=settings.http_timeout, follow_redirects=False, cookies=cookies) as client:
        response = client.get("https://api.bilibili.com/x/web-interface/nav", headers={"User-Agent": "Mozilla/5.0", "Referer": "https://www.bilibili.com/"})
        response.raise_for_status()
        data = response.json()
    user = data.get("data") or {}
    if data.get("code") != 0 or user.get("isLogin") is not True or not isinstance(user.get("mid"), int) or user["mid"] <= 0:
        raise ValueError("unverified platform identity")
    return str(user["mid"]), str(user.get("uname") or "B站用户")[:100]


def exchange_douyin(code: str) -> tuple[str, str]:
    with httpx.Client(timeout=settings.http_timeout, follow_redirects=False) as client:
        response = client.post("https://open.douyin.com/oauth/access_token/", data={
            "client_key": settings.douyin_client_key, "client_secret": settings.douyin_client_secret,
            "code": code, "grant_type": "authorization_code",
        })
        response.raise_for_status()
        data = response.json().get("data") or {}
        if data.get("error_code", 0) != 0 or not data.get("access_token") or not data.get("open_id"):
            raise ValueError("invalid token exchange")
        subject = data["open_id"]
        response = client.get("https://open.douyin.com/oauth/userinfo/", params={"open_id": subject}, headers={"access-token": data["access_token"]})
        response.raise_for_status()
        info = response.json().get("data") or {}
        if info.get("error_code", 0) != 0 or info.get("open_id") != subject or not isinstance(subject, str) or len(subject) > 256:
            raise ValueError("invalid identity response")
        return subject, str(info.get("nickname") or "抖音用户")[:100]


def mark_verified(session: Session, row: Attempt, subject: str, nickname: str, cookies: dict | None = None):
    changed = session.execute(update(Attempt).where(Attempt.state_hash == row.state_hash, Attempt.status == "checking")
        .values(subject=subject, nickname=nickname, status="verified", key_blob=None,
                cookie_blob=encrypt(json.dumps(cookies), aad=row.state_hash) if cookies else None)).rowcount
    session.commit()
    if not changed:
        raise ValueError("authorization cancelled")


@router.post("/platform-auth/image")
def image(body: ProofBody, x_getvideo_session: str | None = Header(None), session: Session = Depends(db)):
    row = authorized_attempt(body, session, x_getvideo_session)
    if row.platform != "bilibili" or not row.key_blob or row.status not in ("waiting", "checking"):
        raise HTTPException(409, "二维码不可用")
    qr_url = json.loads(decrypt(row.key_blob, aad=row.state_hash))["qr_url"]
    output = BytesIO(); qrcode.make(qr_url).save(output, format="PNG")
    return Response(output.getvalue(), media_type="image/png", headers={"Cache-Control": "no-store"})


@router.post("/platform-auth/status")
def status(body: ProofBody, x_getvideo_session: str | None = Header(None), session: Session = Depends(db)):
    row = authorized_attempt(body, session, x_getvideo_session)
    if row.platform == "bilibili" and row.status == "waiting":
        key = json.loads(decrypt(row.key_blob, aad=row.state_hash))["key"]
        changed = session.execute(update(Attempt).where(Attempt.state_hash == row.state_hash, Attempt.status == "waiting").values(status="checking")).rowcount
        session.commit()
        if changed:
            try:
                result = BilibiliLogin().poll(key)
                if result.state is QrState.CONFIRMED and result.cookies:
                    subject, nickname = verify_bilibili(result.cookies)
                    # An unbind/cancel during the provider request must win.
                    session.refresh(row)
                    if row.status == "checking":
                        mark_verified(session, row, subject, nickname, result.cookies)
                else:
                    session.execute(update(Attempt).where(Attempt.state_hash == row.state_hash, Attempt.status == "checking")
                        .values(status="failed" if result.state in (QrState.FAILED, QrState.EXPIRED) else "waiting"))
                    session.commit()
            except (LoginError, httpx.HTTPError, ValueError, KeyError, TypeError):
                session.execute(update(Attempt).where(Attempt.state_hash == row.state_hash, Attempt.status == "checking").values(status="failed"))
                session.commit()
    session.refresh(row)
    return {"status": row.status, "nickname": row.nickname, "message": "平台身份已核验，请确认" if row.status == "verified" else "等待授权" if row.status in ("waiting", "checking") else "授权失败或已结束，请重新发起"}


@router.get("/platform-auth/douyin/callback", response_class=HTMLResponse)
def callback(state: str = Query(min_length=32, max_length=128), code: str | None = Query(None, max_length=1024), session: Session = Depends(db)):
    row = session.get(Attempt, token_hash(state))
    if row is None or row.platform != "douyin" or row.expires_at.replace(tzinfo=timezone.utc) <= utcnow():
        raise HTTPException(400, "授权状态无效或已过期")
    changed = session.execute(update(Attempt).where(Attempt.state_hash == row.state_hash, Attempt.status == "waiting").values(status="checking")).rowcount
    session.commit()
    if not changed:
        raise HTTPException(409, "此授权已处理，请返回 GetVideo")
    try:
        if not code:
            raise ValueError("authorization denied")
        subject, nickname = exchange_douyin(code)
        session.refresh(row)
        if row.status != "checking":
            raise ValueError("authorization cancelled")
        mark_verified(session, row, subject, nickname)
        text = "授权已完成，请返回 GetVideo 点击检查授权并确认。"
    except (httpx.HTTPError, ValueError, KeyError, TypeError):
        session.execute(update(Attempt).where(Attempt.state_hash == row.state_hash, Attempt.status == "checking").values(status="failed"))
        session.commit()
        text = "授权未完成，请返回 GetVideo 重新发起。"
    return HTMLResponse("<!doctype html><meta charset='utf-8'><meta name='viewport' content='width=device-width'><p>" + text + "</p>",
        headers={"Cache-Control": "no-store", "Referrer-Policy": "no-referrer", "Content-Security-Policy": "default-src 'none'"})


@router.post("/platform-auth/complete")
def complete(body: ProofBody, x_getvideo_session: str | None = Header(None), session: Session = Depends(db)):
    row = authorized_attempt(body, session, x_getvideo_session)
    if row.status != "verified" or not row.subject:
        raise HTTPException(409, "请先完成平台授权；此操作只能确认一次")
    # CAS and the binding/session changes share one transaction.
    changed = session.execute(update(Attempt).where(Attempt.state_hash == row.state_hash, Attempt.status == "verified").values(status="consumed")).rowcount
    if not changed:
        session.rollback(); raise HTTPException(409, "此授权已确认")
    binding = session.scalar(select(Binding).where(Binding.platform == row.platform, Binding.subject == row.subject))
    if row.mode == "bind":
        other = session.scalar(select(Binding).where(Binding.user_id == row.user_id, Binding.platform == row.platform))
        if (binding and binding.user_id != row.user_id) or (other and other.subject != row.subject):
            session.rollback(); raise HTTPException(409, "该平台账号已绑定其他手机号，或当前手机号已绑定此平台的其他账号；请先在原账号解除绑定，再重新关联")
        if binding is None:
            binding = Binding(user_id=row.user_id, platform=row.platform, subject=row.subject, nickname=row.nickname)
            session.add(binding)
        user = session.get(AppUser, row.user_id)
    else:
        if binding is None:
            session.rollback(); raise HTTPException(403, "此平台账号尚未绑定手机号，请先用手机号注册并登录，再绑定平台账号")
        user = session.get(AppUser, binding.user_id)
    if user is None:
        session.rollback(); raise HTTPException(403, "手机号账号不可用")
    if row.cookie_blob:
        cookies = json.loads(decrypt(row.cookie_blob, aad=row.state_hash))
        cred = session.scalar(select(PlatformCredential).where(PlatformCredential.user_id == user.id, PlatformCredential.platform == row.platform))
        if cred is None:
            cred = PlatformCredential(user_id=user.id, platform=row.platform); session.add(cred)
        cred.cookie_blob = encrypt("; ".join(f"{k}={v}" for k, v in cookies.items()), aad=credential_aad(user.id, row.platform))
        cred.status, cred.last_checked_at = "active", utcnow()
    row.cookie_blob = None
    try:
        session.flush()
        result = new_session(session, user) if row.mode == "login" else None
        if row.mode == "bind": session.commit()
    except IntegrityError:
        session.rollback(); raise HTTPException(409, "绑定冲突，请刷新后重试") from None
    return {"bound": True, "platform": row.platform, "nickname": row.nickname, "session": result}


class UnbindBody(BaseModel):
    """解绑二次确认：带上当前账号密码，避免拿到已登录设备就能解绑。"""
    password: SecretStr | None = None


@router.post("/platform-bindings/{platform}/unbind", dependencies=[Depends(limit_auth)])
def unbind(platform: Literal["bilibili", "douyin"], body: UnbindBody | None = None,
           user: AppUser = Depends(current_user), session: Session = Depends(db)):
    # 传了密码就校验；没传时按 GETVIDEO_STRICT_UNBIND 决定是否拒绝，保证旧客户端不被直接打断。
    if body is not None and body.password is not None:
        if not password_matches(body.password.get_secret_value(), user.password_hash):
            raise HTTPException(403, "密码不正确，未解除绑定")
    elif os.getenv("GETVIDEO_STRICT_UNBIND") == "1":
        raise HTTPException(400, "解除绑定需要输入当前账号密码")
    session.execute(delete(Binding).where(Binding.user_id == user.id, Binding.platform == platform))
    session.execute(update(Attempt).where(Attempt.user_id == user.id, Attempt.platform == platform).values(status="failed", key_blob=None, cookie_blob=None))
    session.commit()
    return {"ok": True}


@router.post("/platform-auth/cancel")
def cancel(body: ProofBody, x_getvideo_session: str | None = Header(None), session: Session = Depends(db)):
    row = authorized_attempt(body, session, x_getvideo_session)
    session.execute(update(Attempt).where(Attempt.state_hash == row.state_hash, Attempt.status != "consumed")
                    .values(status="failed", key_blob=None, cookie_blob=None))
    session.commit()
    return {"ok": True}
