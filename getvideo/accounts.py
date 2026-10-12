"""Phone/password accounts. Session tokens never share nginx's Authorization header."""
from __future__ import annotations

import hashlib
import hmac
import re
import secrets
import threading
import time
from datetime import timedelta, timezone
from uuid import uuid4

from fastapi import APIRouter, Depends, Header, HTTPException, Request
from pydantic import BaseModel, ConfigDict, Field, SecretStr
from sqlalchemy import select, delete
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from .db import SessionLocal
from .models import AppUser, AppSession, utcnow

router = APIRouter(prefix="/account", tags=["account"])


def db():
    with SessionLocal() as session:
        yield session


def normalize_phone(value: str) -> str:
    value = re.sub(r"[\s()-]", "", value)
    if value.startswith("0086"):
        value = "+86" + value[4:]
    if value.startswith("+86"):
        value = value[3:]
    if not re.fullmatch(r"1[3-9]\d{9}", value):
        raise HTTPException(400, "请输入中国大陆 11 位手机号（可带 +86）")
    return "+86" + value


def password_hash(password: str, salt: str | None = None) -> str:
    salt = salt or secrets.token_hex(16)
    digest = hashlib.scrypt(password.encode(), salt=bytes.fromhex(salt), n=16384, r=8, p=1).hex()
    return f"scrypt${salt}${digest}"


def password_matches(password: str, stored: str) -> bool:
    _, salt, _ = stored.split("$")
    return hmac.compare_digest(password_hash(password, salt), stored)


_DUMMY_PASSWORD_HASH = password_hash("unavailable-user", "00" * 16)


def token_hash(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def authenticated_user(token: str | None, session: Session) -> AppUser:
    if not token or len(token) > 128:
        raise HTTPException(401, "请先登录 GetVideo 账号")
    row = session.get(AppSession, token_hash(token))
    if row is None or row.expires_at.replace(tzinfo=timezone.utc) <= utcnow():
        raise HTTPException(401, "账号登录已过期，请重新登录")
    user = session.get(AppUser, row.user_id)
    if user is None:
        raise HTTPException(401, "账号不可用")
    return user


def current_user(x_getvideo_session: str | None = Header(None), session: Session = Depends(db)) -> AppUser:
    return authenticated_user(x_getvideo_session, session)


# Bound per-IP windows; proxy must overwrite X-Forwarded-For, otherwise use socket IP.
_windows: dict[str, list[float]] = {}
_lock = threading.Lock()


def limit_auth(request: Request):
    key = request.client.host if request.client else "unknown"
    now = time.monotonic()
    with _lock:
        for old in list(_windows):
            if not _windows[old] or now - _windows[old][-1] > 300:
                del _windows[old]
        stamps = [t for t in _windows.get(key, []) if now - t < 300]
        if len(stamps) >= 20 or (key not in _windows and len(_windows) >= 10000):
            raise HTTPException(429, "登录尝试过于频繁，请稍后再试")
        stamps.append(now)
        _windows[key] = stamps


class Credentials(BaseModel):
    model_config = ConfigDict(extra="forbid")
    phone: str = Field(min_length=11, max_length=32)
    password: SecretStr = Field(min_length=8, max_length=128)


def user_out(user: AppUser) -> dict:
    return {"user_id": user.id, "phone": user.phone}


def new_session(session: Session, user: AppUser) -> dict:
    token = secrets.token_urlsafe(32)
    expires = utcnow() + timedelta(days=30)
    session.add(AppSession(token_hash=token_hash(token), user_id=user.id, expires_at=expires))
    session.execute(delete(AppSession).where(AppSession.expires_at < utcnow()))
    session.commit()
    return {**user_out(user), "session_token": token, "expires_at": expires.isoformat()}


@router.post("/register", dependencies=[Depends(limit_auth)])
def register(req: Credentials, session: Session = Depends(db)):
    phone = normalize_phone(req.phone)
    user = AppUser(id="acct_" + uuid4().hex, phone=phone, password_hash=password_hash(req.password.get_secret_value()))
    session.add(user)
    try:
        session.flush()
    except IntegrityError:
        session.rollback()
        raise HTTPException(409, "该手机号已注册，请登录") from None
    return new_session(session, user)


@router.post("/login", dependencies=[Depends(limit_auth)])
def login(req: Credentials, session: Session = Depends(db)):
    phone = normalize_phone(req.phone)
    user = session.scalar(select(AppUser).where(AppUser.phone == phone))
    # Match work for missing users to avoid a trivial timing oracle.
    stored = user.password_hash if user else _DUMMY_PASSWORD_HASH
    if not password_matches(req.password.get_secret_value(), stored) or user is None:
        raise HTTPException(401, "手机号或密码不正确")
    return new_session(session, user)


@router.get("/me")
def me(user: AppUser = Depends(current_user)):
    return user_out(user)


@router.post("/logout")
def logout(x_getvideo_session: str | None = Header(None), session: Session = Depends(db)):
    if x_getvideo_session:
        session.execute(delete(AppSession).where(AppSession.token_hash == token_hash(x_getvideo_session)))
        session.commit()
    return {"ok": True}


async def protect_account_ids(request: Request):
    """Legacy clients retain guest IDs, but cannot impersonate registered accounts."""
    if request.url.path.startswith(("/account/", "/agent/v2/", "/v2/")):
        return
    identity = request.path_params.get("user_id")
    if request.method in {"POST", "PUT"} and "application/json" in request.headers.get("content-type", ""):
        try:
            body = await request.json()
            if isinstance(body, dict):
                identity = body.get("user_id", identity)
        except ValueError:
            return
    if not isinstance(identity, str) or not identity.startswith("acct_"):
        return
    with SessionLocal() as session:
        user = authenticated_user(request.headers.get("x-getvideo-session"), session)
        if user.id != identity:
            raise HTTPException(403, "不能访问其他账号的数据")
