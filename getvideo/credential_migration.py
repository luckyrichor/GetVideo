"""Administrator-issued device migration tickets; UUIDs alone never claim credentials."""
import json
import secrets
from datetime import timedelta, timezone
from fastapi import HTTPException
from sqlalchemy import select
from .accounts import token_hash
from .db import session_scope
from .models import CredentialMigrationTicket, PlatformCredential, utcnow
from .crypto import encrypt, decrypt, credential_aad
from . import credentials


def issue_ticket(guest_id, expires_in=600):
    if not guest_id or guest_id.startswith('acct_') or len(guest_id) > 64:
        raise ValueError('A verified guest source is required')
    token = secrets.token_urlsafe(32)
    with session_scope() as s:
        s.add(CredentialMigrationTicket(token_hash=token_hash(token), guest_id=guest_id,
                                       expires_at=utcnow() + timedelta(seconds=expires_in)))
    return token


def claim(ticket, user_id):
    with session_scope() as s:
        row = s.get(CredentialMigrationTicket, token_hash(ticket))
        if row is None:
            raise HTTPException(404, '迁移凭据无效')
        if row.claimed_by:
            if row.claimed_by != user_id:
                raise HTTPException(409, '迁移凭据已使用')
            platforms = json.loads(row.platforms_json)
        else:
            if row.expires_at.replace(tzinfo=timezone.utc) < utcnow():
                raise HTTPException(410, '迁移凭据已过期，请重新授权平台')
            sources = list(s.scalars(select(PlatformCredential).where(PlatformCredential.user_id == row.guest_id)))
            platforms = [p.platform for p in sources]
            targets = list(s.scalars(select(PlatformCredential).where(PlatformCredential.user_id == user_id,
                                                                      PlatformCredential.platform.in_(platforms))))
            if targets:
                raise HTTPException(409, '当前账号已有平台授权，请保留现有授权或重新授权')
            for source in sources:
                plaintext = decrypt(source.cookie_blob, aad=credential_aad(source.user_id, source.platform))
                s.add(PlatformCredential(user_id=user_id, platform=source.platform,
                                        cookie_blob=encrypt(plaintext, aad=credential_aad(user_id, source.platform)),
                                        status=source.status, last_checked_at=source.last_checked_at))
                s.delete(source)
            row.claimed_by = user_id
            row.platforms_json = json.dumps(platforms)
        guest_id = row.guest_id
    # Files are caches. Remove stale source on every retry; only active DB rows can be exported.
    for platform in platforms:
        credentials._cookie_path(guest_id, platform).unlink(missing_ok=True)
    return {'platforms': platforms}
