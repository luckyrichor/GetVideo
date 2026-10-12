"""数据库 session 管理。"""
from __future__ import annotations

from contextlib import contextmanager
from typing import Iterator

from sqlalchemy import create_engine, event, func, select, text
from sqlalchemy.orm import Session, sessionmaker

from .config import settings
from .models import Base, Track

_is_sqlite = settings.database_url.startswith("sqlite")

engine = create_engine(
    settings.database_url,
    future=True,
    connect_args={"check_same_thread": False} if _is_sqlite else {},
)

if _is_sqlite:
    @event.listens_for(engine, "connect")
    def _sqlite_pragmas(dbapi_conn, _record):  # pragma: no cover
        cur = dbapi_conn.cursor()
        # WAL：下载 worker 写库时不阻塞同步接口的读
        cur.execute("PRAGMA journal_mode=WAL")
        cur.execute("PRAGMA foreign_keys=ON")
        cur.close()

SessionLocal = sessionmaker(bind=engine, expire_on_commit=False, future=True)


def init_db() -> None:
    from .schema_migrations import migrate_tracks
    migrate_tracks(engine)
    Base.metadata.create_all(engine)


@contextmanager
def session_scope() -> Iterator[Session]:
    session = SessionLocal()
    try:
        if session.bind.dialect.name == "sqlite":
            # 先取得写锁再读取，保证 max+1 与提交顺序一致。
            session.execute(text("BEGIN IMMEDIATE"))
        yield session
        session.commit()
    except Exception:
        session.rollback()
        raise
    finally:
        session.close()


def next_updated_seq(session: Session) -> int:
    """分配下一个同步游标值。

    必须在 session_scope 写事务内调用。SQLite 的 BEGIN IMMEDIATE
    将读取、分配与提交串行化，避免分页同步漏记录。
    """
    current = session.execute(select(func.max(Track.updated_seq))).scalar()
    return (current or 0) + 1
