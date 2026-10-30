"""服务端响应 ↔ Android DTO 的字段契约校验。

字段名对不上不会在编译期暴露，只会在用户手机上炸成一个空列表或
崩溃。这个用例直接解析 Kotlin 源码里的 @Serializable data class，
把字段名和服务端 Pydantic 模型逐一对齐。
"""
from __future__ import annotations
from getvideo import platform_accounts

import re
from pathlib import Path

import pytest

from getvideo import api as server_api
from getvideo import account_api

DTO_FILE = Path(__file__).resolve().parent.parent / "android/app/src/main/java/com/getvideo/app/data/remote/Dto.kt"

pytestmark = pytest.mark.skipif(not DTO_FILE.exists(), reason="Android 工程不存在")

_CLASS_RE = re.compile(r"data class (\w+)\(\n(.*?)\n\)", re.S)
_FIELD_RE = re.compile(
    r"(?:@SerialName\(\"(?P<ser>[^\"]+)\"\)\s*)?"
    r"val\s+(?P<name>\w+)\s*:",
)


def _kotlin_classes() -> dict[str, set[str]]:
    """解析出 {类名: {线上字段名}}。有 @SerialName 就用它，否则用属性名。"""
    src = DTO_FILE.read_text(encoding="utf-8")
    out: dict[str, set[str]] = {}
    for name, body in _CLASS_RE.findall(src):
        fields = set()
        for line in body.split("\n"):
            if m := _FIELD_RE.search(line):
                fields.add(m.group("ser") or m.group("name"))
        out[name] = fields
    return out


def _pydantic_fields(model) -> set[str]:
    return {(f.alias or n) for n, f in model.model_fields.items()}


KOTLIN = _kotlin_classes()


@pytest.mark.parametrize(
    "kotlin_name,server_model",
    [
        ("PlatformAuthStart", platform_accounts.StartBody),
        ("PlatformAuthProof", platform_accounts.ProofBody),
        ("TrackDto", server_api.TrackOut),
        ("ExtractRequest", account_api.ExtractBody),
        ("ExtractResponse", server_api.ExtractResponse),
        ("SyncResponse", server_api.SyncResponse),
        ("LoginStartRequest", account_api.PlatformBody),
        ("LoginStartResponse", server_api.LoginStartResponse),
        ("LoginPollResponse", server_api.LoginPollResponse),
        ("CookieImportRequest", account_api.CookieBody),
        ("CredentialDto", server_api.CredentialOut),
        ("AgentChatRequest", server_api.AgentChatRequest),
        ("AgentLocalTrack", server_api.AgentLocalTrack),
        ("AgentChatResponse", server_api.AgentChatResponse),
        ("AgentMemoryRequest", server_api.AgentMemoryRequest),
        ("AgentMemoryUpdate", server_api.AgentMemoryUpdate),
        ("AgentMemoryOut", server_api.AgentMemoryOut),
    ],
)
def test_dto_matches_server_model(kotlin_name, server_model):
    kotlin = KOTLIN.get(kotlin_name)
    assert kotlin, f"Dto.kt 里没有 {kotlin_name}"

    server = _pydantic_fields(server_model)

    missing = server - kotlin
    assert not missing, f"{kotlin_name} 缺少服务端字段：{sorted(missing)}"

    extra = kotlin - server
    assert not extra, f"{kotlin_name} 多出服务端没有的字段：{sorted(extra)}"


def test_all_api_routes_are_declared_in_kotlin():
    """服务端每条路由都应该在 GetVideoApi.kt 里有对应方法。"""
    api_kt = DTO_FILE.parent / "GetVideoApi.kt"
    src = api_kt.read_text(encoding="utf-8")

    # 这些不面向 App：健康检查、二维码图片（用 Coil 直接按 URL 加载）、
    # 视频下载（用 OkHttp 流式下载，不经 Retrofit）
    skip = {"/health", "/login/qr/{session_id}/image", "/video/{track_id}", "/login/platforms"}

    skip.add("/account/platform-auth/douyin/callback")
    skip.update({"/v2/recreations/{pid}/source", "/v2/recreations/{pid}/runs/{rid}/video"})
    skip.update({"/v2/platforms", "/v2/platforms/qr/{sid}/image", "/v2/video/{track_id}"})
    # 博主批量 / 视频库管理 / 维护接口是后端先行能力，当前 APK 尚未接。
    skip.update({path for path in server_api.app.openapi()["paths"]
                 if path.startswith(("/v2/creators/", "/v2/maintenance/", "/v2/tracks/"))})
    skip.update({"/v2/resolve", "/v2/extraction-jobs", "/v2/scheduler", "/v2/scheduler/run-once"})
    for path in server_api.app.openapi()["paths"]:
        # Legacy admin/guest API is deliberately no longer the mobile contract.
        if not path.startswith(("/account/", "/v2/", "/agent/v2/")):
            continue
        if not path or path in skip or path.startswith("/openapi") or path.startswith("/docs") or path.startswith("/redoc"):
            continue
        # Retrofit 的路径不带前导斜杠，占位符名字也可能不同 —— 比对去掉占位符的骨架
        skeleton = re.sub(r"\{[^}]+\}", "{}", path.lstrip("/"))
        found = any(
            re.sub(r"\{[^}]+\}", "{}", m) == skeleton
            for m in re.findall(r'@(?:GET|POST|PUT|DELETE)\("([^"]+)"\)', src)
        )
        assert found, f"Android 端没有实现服务端路由 {path}"
