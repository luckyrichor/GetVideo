"""在导入应用前隔离测试数据，避免清理用例触碰实际视频库。"""
import base64
import os
from pathlib import Path
from tempfile import TemporaryDirectory

_test_dir = TemporaryDirectory(prefix="getvideo-tests-")
_root = Path(_test_dir.name)
os.environ["GETVIDEO_DATABASE_URL"] = f"sqlite:///{_root / 'test.db'}"
for _name in ("audio", "tmp", "cookie", "recreation"):
    os.environ[f"GETVIDEO_{_name.upper()}_DIR"] = str(_root / _name)
os.environ["GETVIDEO_SECRET_KEY"] = base64.urlsafe_b64encode(os.urandom(32)).decode()
os.environ["GETVIDEO_OLLAMA_MODEL"] = ""
os.environ["ARK_API_KEY"] = ""
# 测试里不启动后台博主 worker；需要时直接调用 creator_service.process_job。
os.environ["GETVIDEO_CREATOR_WORKER_ENABLED"] = "0"
# 测试环境没有 Chromium，抖音一律走直连/测试替身。
os.environ["GETVIDEO_DOUYIN_BROWSER"] = "0"
