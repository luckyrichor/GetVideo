"""运行期配置。所有路径都相对项目根，方便整目录搬迁。"""
from __future__ import annotations

from pathlib import Path

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict

ROOT = Path(__file__).resolve().parent.parent


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="GETVIDEO_", env_file=str(ROOT / ".env"), extra="ignore")

    # P0 先用 SQLite：换 Postgres 只要改这一行 URL，ORM 层不动
    database_url: str = f"sqlite:///{ROOT / 'data' / 'getvideo.db'}"

    audio_dir: Path = ROOT / "data" / "video"
    tmp_dir: Path = ROOT / "data" / "tmp"
    cookie_dir: Path = ROOT / "data" / "cookies"

    # 目标视频格式。MP4/H.264/AAC 兼容性最好，Android 原生解码
    audio_format: str = "mp4"
    audio_quality: str = "0"  # yt-dlp: 0 = 保留源码率

    http_timeout: float = 20.0
    max_redirects: int = 10

    # 设置模型名才启用 AI；默认本地规则，不产生外部调用。
    ollama_url: str = "http://127.0.0.1:11434"
    ollama_model: str = ""
    ark_api_key: str = Field(default="", validation_alias="ARK_API_KEY")
    ark_url: str = "https://ark.cn-beijing.volces.com/api/v3"
    ark_models: str = "doubao-seed-2-0-mini-260428,doubao-seed-2-1-pro-260915"
    classify_timeout: float = 60.0

    # Only server-side credentials; OAuth is disabled until all three are configured.
    douyin_client_key: str = ""
    douyin_client_secret: str = ""
    douyin_redirect_uri: str = ""

    def ensure_dirs(self) -> None:
        for d in (self.audio_dir, self.tmp_dir, self.cookie_dir):
            d.mkdir(parents=True, exist_ok=True)


settings = Settings()
settings.ensure_dirs()
