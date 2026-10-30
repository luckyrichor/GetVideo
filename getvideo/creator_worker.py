"""博主批量后台 worker。

单进程单线程顺序执行，避免同时打平台接口。任务状态都在 SQLite 里，
进程重启时把中断的 running 任务归还 pending，视频下载本身可重入，
不会因为重启丢任务。
"""
from __future__ import annotations

import logging
import threading

from . import creator_service
from .config import settings

log = logging.getLogger(__name__)

_stop = threading.Event()
_thread: threading.Thread | None = None
_lock = threading.Lock()


def start() -> None:
    global _thread
    if not settings.creator_worker_enabled:
        return
    with _lock:
        if _thread is not None and _thread.is_alive():
            return
        try:
            creator_service.recover_interrupted_jobs()
        except Exception as exc:  # 启动恢复失败不应拖垮整个 API
            log.warning("博主任务恢复失败: %s", exc)
        _stop.clear()
        _thread = threading.Thread(target=_loop, name="getvideo-creator-worker", daemon=True)
        _thread.start()
        log.info("博主批量 worker 已启动")


def stop() -> None:
    global _thread
    with _lock:
        _stop.set()
        thread, _thread = _thread, None
    if thread is not None:
        thread.join(timeout=5)
        log.info("博主批量 worker 已停止")


def _loop() -> None:
    while not _stop.is_set():
        try:
            job_id = creator_service.claim_next_job()
        except Exception as exc:
            log.warning("博主任务领取失败: %s", exc)
            job_id = None
        if job_id is None:
            _stop.wait(max(0.2, float(settings.creator_worker_poll_seconds)))
            continue
        log.info("开始执行博主任务 %s", job_id)
        try:
            creator_service.process_job(job_id)
        except Exception as exc:
            log.exception("博主任务 %s 执行异常", job_id)
            try:
                creator_service.fail_job(job_id, f"后台执行异常：{exc}")
            except Exception:
                log.exception("博主任务 %s 标记失败时再次异常", job_id)
