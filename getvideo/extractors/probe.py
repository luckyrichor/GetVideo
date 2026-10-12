"""用 ffprobe 读成品文件的真实参数。

平台接口给的 duration 描述的是「视频」，而我们取到的可能是另一路
音视频轨（比如抖音的原声片段）。库里的时长必须以文件实测为准，否则
手机端进度条和排序全是错的。
"""
from __future__ import annotations

import json
import logging
import subprocess
from pathlib import Path

log = logging.getLogger(__name__)


def probe_duration(path: Path) -> float | None:
    """返回秒数；探测失败返回 None（调用方应保留原有值）。"""
    cmd = [
        "ffprobe", "-v", "error",
        "-show_entries", "format=duration",
        "-of", "json", str(path),
    ]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
        if proc.returncode != 0:
            log.warning("ffprobe 失败: %s", proc.stderr.strip()[:200])
            return None
        value = json.loads(proc.stdout).get("format", {}).get("duration")
        return float(value) if value else None
    except (subprocess.TimeoutExpired, json.JSONDecodeError, ValueError, OSError) as exc:
        log.warning("ffprobe 异常: %s", exc)
        return None
