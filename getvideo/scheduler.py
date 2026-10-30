"""单机持久化抓取任务。独立 worker 运行，不随 HTTP worker 重复启动。"""
from __future__ import annotations

import fcntl
import json
import logging
import re
import time
from pathlib import Path

import yaml
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from yt_dlp import YoutubeDL

from .config import ROOT, settings
from .db import session_scope
from .models import CrawlState
from .pipeline import cookie_file_for, extract
from .sharelink import Platform

log = logging.getLogger(__name__)


class Group(BaseModel):
    model_config = ConfigDict(extra="forbid")
    name: str = Field(min_length=1, max_length=128)
    interval: str = "12h"
    top_n: int = Field(default=20, ge=1, le=100)
    keywords: list[str] = Field(default_factory=list, max_length=100)
    urls: list[str] = Field(default_factory=list, max_length=100)
    enabled: bool = True
    user_id: str | None = None

    @field_validator("interval")
    @classmethod
    def valid_interval(cls, value):
        if not re.fullmatch(r"[1-9]\d*[mhd]", value):
            raise ValueError("interval 应为正整数加 m/h/d，例如 30m、12h")
        return value

    @field_validator("keywords", "urls")
    @classmethod
    def nonempty(cls, values):
        if any(not value.strip() for value in values):
            raise ValueError("关键词和链接不能为空")
        return [value.strip() for value in values]

    @model_validator(mode="after")
    def needs_source(self):
        if not self.keywords and not self.urls:
            raise ValueError("每组至少配置 keywords 或 urls")
        return self

    @property
    def seconds(self) -> int:
        return int(self.interval[:-1]) * {"m": 60, "h": 3600, "d": 86400}[self.interval[-1]]


class Schedule(BaseModel):
    model_config = ConfigDict(extra="forbid")
    groups: list[Group]

    @model_validator(mode="after")
    def unique_names(self):
        if len({g.name for g in self.groups}) != len(self.groups):
            raise ValueError("任务组名称不能重复")
        return self


def load_schedule(path: Path) -> Schedule:
    return Schedule.model_validate(yaml.safe_load(path.read_text(encoding="utf-8")))


def search_bilibili(keyword: str, limit: int, user_id: str | None = None) -> list[str]:
    cookie = cookie_file_for(user_id, Platform.BILIBILI)
    options = {"quiet": True, "extract_flat": True, "skip_download": True,
               "socket_timeout": settings.http_timeout, "retries": 1,
               "extractor_retries": 1, "cachedir": False}
    if cookie:
        options["cookiefile"] = str(cookie)
    with YoutubeDL(options) as ydl:
        result = ydl.extract_info(f"bilisearch{limit}:{keyword}", download=False)
    return [entry["url"] for entry in (result or {}).get("entries", []) if entry and entry.get("url")]


def run_group(group: Group) -> dict:
    urls = list(dict.fromkeys(group.urls))[:group.top_n]
    summary = {"name": group.name, "added": 0, "reused": 0, "failed": 0, "errors": []}
    # top_n 是整组的总提取上限，按关键词轮换避免第一项长期独占配额。
    pools = []
    for keyword in group.keywords:
        try:
            pools.append(search_bilibili(keyword, group.top_n, group.user_id))
        except Exception as exc:
            summary["failed"] += 1
            summary["errors"].append(f"搜索 {keyword}: {type(exc).__name__}")
    for i in range(group.top_n):
        for pool in pools:
            if i < len(pool) and pool[i] not in urls and len(urls) < group.top_n:
                urls.append(pool[i])
    for url in urls:
        try:
            result = extract(url, user_id=group.user_id)
            summary["reused" if result.reused else "added"] += 1
        except Exception as exc:
            summary["failed"] += 1
            summary["errors"].append(f"提取失败: {type(exc).__name__}")
            log.warning("抓取组 %s 提取失败 (%s)", group.name, type(exc).__name__)
    return summary


def tick(schedule: Schedule, *, now: float | None = None, force: bool = False) -> list[dict]:
    now = time.time() if now is None else now
    summaries = []
    # 同一数据目录只允许一个 worker 执行；进程退出由内核自动释放。
    lock_path = settings.tmp_dir / "scheduler.lock"
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    with lock_path.open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return summaries
        for group in schedule.groups:
            if not group.enabled:
                continue
            with session_scope() as s:
                state = s.get(CrawlState, group.name)
                if not force and state and state.next_run > now:
                    continue
            result = run_group(group)
            with session_scope() as s:
                state = s.get(CrawlState, group.name)
                if state is None:
                    state = CrawlState(name=group.name)
                    s.add(state)
                state.last_run = now
                # 失败最多五分钟后重试，成功记录由提取链路自动去重。
                delay = min(group.seconds, 300) if result["failed"] else group.seconds
                state.next_run = now + delay
                state.summary_json = json.dumps(result, ensure_ascii=False)
            summaries.append(result)
    return summaries


def run(path: Path = ROOT / "config/keywords.yaml", *, once: bool = False, force: bool = False) -> None:
    while True:
        schedule = load_schedule(path)
        for result in tick(schedule, force=force):
            print(json.dumps(result, ensure_ascii=False), flush=True)
        if once:
            return
        force = False
        time.sleep(30)
