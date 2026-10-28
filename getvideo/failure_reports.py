"""Authenticated, bounded failure ingestion. Original content is never written to access logs."""
import json
import re
from typing import Annotated
from uuid import UUID
from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session
from .accounts import current_user, db
from .models import AppUser, ExtractionFailure

router = APIRouter(prefix='/v2/diagnostics/extractions', tags=['diagnostics'])
_SECRET = re.compile(r'(?i)cookie|authorization|password|密码|token|session|api[_-]?key|secret|凭据')
_SHARE_SECRET = re.compile(r'(?i)((?:cookie|authorization|password|密码|token|session|api[_-]?key|secret)\s*[:=]\s*)[^\r\n]*')


def sanitize_log(line: str) -> str:
    if _SECRET.search(line):
        return '[敏感内容已省略]'
    return re.sub(r'https?://\S+', '[地址已省略]', line)


class FailureIn(BaseModel):
    id: UUID
    version: str = Field(min_length=1, max_length=64)
    occurred_at_ms: int = Field(ge=0)
    share_text: str = Field(min_length=1, max_length=10000)
    logs: list[Annotated[str, Field(max_length=1000)]] = Field(max_length=80)


def detail(row):
    return {'id': row.report_id, 'version': row.version, 'occurred_at_ms': row.occurred_at_ms,
            'received_at': row.received_at.isoformat(), 'share_text': row.share_text, 'logs': json.loads(row.logs_json)}


@router.post('')
def record(body: FailureIn, user: AppUser = Depends(current_user), session: Session = Depends(db)):
    key = (user.id, str(body.id))
    row = session.get(ExtractionFailure, key)
    if row is None:
        row = ExtractionFailure(owner_id=user.id, report_id=str(body.id), version=body.version,
            occurred_at_ms=body.occurred_at_ms,
            share_text=re.sub(r'(https?://)[^\s/@]+:[^\s/@]+@', r'\1[凭据已省略]@', _SHARE_SECRET.sub(r'\1[已省略]', body.share_text)),
            logs_json=json.dumps([sanitize_log(line) for line in body.logs], ensure_ascii=False))
        session.add(row)
        try:
            session.commit()
        except IntegrityError:
            session.rollback()
            if session.get(ExtractionFailure, key) is None:
                raise
    return {'id': str(body.id), 'stored': True}


@router.get('')
def recent(user: AppUser = Depends(current_user), session: Session = Depends(db), limit: int = Query(50, ge=1, le=200)):
    rows = session.scalars(select(ExtractionFailure).where(ExtractionFailure.owner_id == user.id)
                           .order_by(ExtractionFailure.received_at.desc()).limit(limit)).all()
    return [detail(row) for row in rows]


@router.get('/{report_id}')
def get(report_id: UUID, user: AppUser = Depends(current_user), session: Session = Depends(db)):
    row = session.get(ExtractionFailure, (user.id, str(report_id)))
    if row is None:
        raise HTTPException(404, '报告不存在')
    return detail(row)


if __name__ == '__main__':
    import argparse
    from .db import SessionLocal
    parser = argparse.ArgumentParser(description='SSH 管理员查看手机直提取失败记录（只读）')
    parser.add_argument('--limit', type=int, default=20)
    parser.add_argument('--id', type=UUID)
    parser.add_argument('--owner')
    args = parser.parse_args()
    with SessionLocal() as session:
        query = select(ExtractionFailure).order_by(ExtractionFailure.received_at.desc())
        if args.id:
            query = query.where(ExtractionFailure.report_id == str(args.id))
        if args.owner:
            query = query.where(ExtractionFailure.owner_id == args.owner)
        rows = session.scalars(query.limit(max(1, min(args.limit, 200)))).all()
        print(json.dumps([dict(detail(row), owner_id=row.owner_id) for row in rows], ensure_ascii=False, indent=2))
