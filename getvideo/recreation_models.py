"""Persistent, account-scoped analysis and generation jobs; no provider secrets."""
from sqlalchemy import String, Text, Float, Integer, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column
from .models import Base, utcnow
from sqlalchemy import DateTime
from datetime import datetime

class RecreationProject(Base):
    __tablename__ = 'recreation_projects'
    __table_args__ = (UniqueConstraint('owner_id', 'request_key'),)
    id: Mapped[str] = mapped_column(String(40), primary_key=True)
    owner_id: Mapped[str] = mapped_column(String(64), index=True)
    request_key: Mapped[str] = mapped_column(String(80))
    title: Mapped[str] = mapped_column(Text)
    source_path: Mapped[str] = mapped_column(Text)
    source_sha256: Mapped[str] = mapped_column(String(64))
    duration: Mapped[float] = mapped_column(Float)
    width: Mapped[int] = mapped_column(Integer)
    height: Mapped[int] = mapped_column(Integer)
    has_audio: Mapped[int] = mapped_column(Integer, default=0)
    analyze_audio: Mapped[int] = mapped_column(Integer, default=1)
    status: Mapped[str] = mapped_column(String(32), default='uploaded', index=True)
    revision: Mapped[int] = mapped_column(Integer, default=0)
    guidance: Mapped[str] = mapped_column(Text, default='')
    error: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)

class RecreationClip(Base):
    __tablename__ = 'recreation_clips'
    __table_args__ = (UniqueConstraint('project_id', 'position'),)
    id: Mapped[str] = mapped_column(String(40), primary_key=True)
    project_id: Mapped[str] = mapped_column(String(40), index=True)
    position: Mapped[int] = mapped_column(Integer)
    start: Mapped[float] = mapped_column(Float)
    end: Mapped[float] = mapped_column(Float)
    status: Mapped[str] = mapped_column(String(32), default='pending')
    description: Mapped[str] = mapped_column(Text, default='')
    original_description: Mapped[str] = mapped_column(Text, default='')
    audio_description: Mapped[str] = mapped_column(Text, default='')
    continuity: Mapped[str] = mapped_column(Text, default='')
    uncertainties: Mapped[str] = mapped_column(Text, default='[]')
    error: Mapped[str | None] = mapped_column(Text)

class RecreationRun(Base):
    __tablename__ = 'recreation_runs'
    __table_args__ = (UniqueConstraint('project_id', 'request_key'),)
    id: Mapped[str] = mapped_column(String(40), primary_key=True)
    project_id: Mapped[str] = mapped_column(String(40), index=True)
    owner_id: Mapped[str] = mapped_column(String(64), index=True)
    request_key: Mapped[str] = mapped_column(String(80))
    revision: Mapped[int] = mapped_column(Integer)
    model: Mapped[str] = mapped_column(String(128))
    resolution: Mapped[str] = mapped_column(String(16))
    mode: Mapped[str] = mapped_column(String(24))
    keep_audio: Mapped[int] = mapped_column(Integer)
    ratio: Mapped[str] = mapped_column(String(16))
    guidance: Mapped[str] = mapped_column(Text)
    status: Mapped[str] = mapped_column(String(32), default='queued', index=True)
    result_path: Mapped[str | None] = mapped_column(Text)
    result_sha256: Mapped[str | None] = mapped_column(String(64))
    result_bytes: Mapped[int | None] = mapped_column(Integer)
    result_track_id: Mapped[int | None] = mapped_column(Integer)
    error: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)

class RecreationRunClip(Base):
    __tablename__ = 'recreation_run_clips'
    __table_args__ = (UniqueConstraint('run_id', 'position'),)
    id: Mapped[str] = mapped_column(String(40), primary_key=True)
    run_id: Mapped[str] = mapped_column(String(40), index=True)
    position: Mapped[int] = mapped_column(Integer)
    start: Mapped[float] = mapped_column(Float)
    end: Mapped[float] = mapped_column(Float)
    description: Mapped[str] = mapped_column(Text)
    continuity: Mapped[str] = mapped_column(Text)
    status: Mapped[str] = mapped_column(String(32), default='pending')
    provider_task_id: Mapped[str | None] = mapped_column(String(128))
    output_path: Mapped[str | None] = mapped_column(Text)
    usage_json: Mapped[str | None] = mapped_column(Text)
    error: Mapped[str | None] = mapped_column(Text)
