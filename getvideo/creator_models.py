"""博主批量任务表。

和 ``extract_jobs`` 分开：一条博主任务会挂多条视频，且要保存
点赞/评论等互动数据、下载进度和失败原因。表是增量新增，不会改已有
``tracks`` 的字段，因此不影响当前 APK 的同步契约。
"""
from __future__ import annotations

from datetime import datetime

from sqlalchemy import (
    BigInteger,
    DateTime,
    Float,
    ForeignKey,
    Index,
    Integer,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from .models import Base, utcnow


class CreatorJob(Base):
    __tablename__ = "creator_jobs"
    __table_args__ = (
        Index("ix_creator_jobs_owner_status", "owner_scope", "status"),
        Index("ix_creator_jobs_owner_created", "owner_scope", "created_at"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    owner_scope: Mapped[str] = mapped_column(String(64), nullable=False, index=True)

    platform: Mapped[str] = mapped_column(String(32), nullable=False)
    creator_id: Mapped[str] = mapped_column(String(128), nullable=False, index=True)
    creator_name: Mapped[str] = mapped_column(Text, nullable=False, default="")
    creator_url: Mapped[str] = mapped_column(Text, nullable=False)
    avatar_url: Mapped[str | None] = mapped_column(Text)

    start_index: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    item_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    total_videos: Mapped[int | None] = mapped_column(Integer)
    include_comments: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    comment_limit: Mapped[int] = mapped_column(Integer, nullable=False, default=5)
    force: Mapped[int] = mapped_column(Integer, nullable=False, default=0)

    # pending | running | done | failed | cancelled
    status: Mapped[str] = mapped_column(String(16), nullable=False, default="pending", index=True)
    processed_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    succeeded_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    failed_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    error: Mapped[str | None] = mapped_column(Text)

    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    started_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    finished_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    items: Mapped[list["CreatorItem"]] = relationship(
        back_populates="job", cascade="all, delete-orphan", order_by="CreatorItem.position"
    )


class CreatorItem(Base):
    __tablename__ = "creator_items"
    __table_args__ = (
        UniqueConstraint("job_id", "video_id", name="uq_creator_item_job_video"),
        Index("ix_creator_items_job_status", "job_id", "status"),
        Index("ix_creator_items_job_position", "job_id", "position"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    job_id: Mapped[int] = mapped_column(ForeignKey("creator_jobs.id"), nullable=False, index=True)

    position: Mapped[int] = mapped_column(Integer, nullable=False)
    platform: Mapped[str] = mapped_column(String(32), nullable=False)
    video_id: Mapped[str] = mapped_column(String(128), nullable=False)
    title: Mapped[str] = mapped_column(Text, nullable=False, default="")
    description: Mapped[str | None] = mapped_column(Text)
    source_url: Mapped[str] = mapped_column(Text, nullable=False)
    cover_url: Mapped[str | None] = mapped_column(Text)
    duration_sec: Mapped[float | None] = mapped_column(Float)
    published_at: Mapped[str | None] = mapped_column(String(40))
    platform_oid: Mapped[int | None] = mapped_column(BigInteger)
    # 抖音列表原始作品 JSON（含播放地址）；不返回给客户端。
    prefetched_json: Mapped[str | None] = mapped_column(Text)

    view_count: Mapped[int | None] = mapped_column(BigInteger)
    like_count: Mapped[int | None] = mapped_column(BigInteger)
    comment_count: Mapped[int | None] = mapped_column(BigInteger)
    share_count: Mapped[int | None] = mapped_column(BigInteger)
    favorite_count: Mapped[int | None] = mapped_column(BigInteger)
    coin_count: Mapped[int | None] = mapped_column(BigInteger)
    danmaku_count: Mapped[int | None] = mapped_column(BigInteger)

    comments_json: Mapped[str | None] = mapped_column(Text)
    comments_status: Mapped[str | None] = mapped_column(String(64))

    # pending | running | done | failed | cancelled
    status: Mapped[str] = mapped_column(String(16), nullable=False, default="pending", index=True)
    track_id: Mapped[int | None] = mapped_column(ForeignKey("tracks.id"))
    reused: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    error: Mapped[str | None] = mapped_column(Text)

    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, onupdate=utcnow)

    job: Mapped[CreatorJob] = relationship(back_populates="items")
