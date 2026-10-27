# GetVideo 工作约定

- 独立视频项目，来源 GetAudio@bd4aa630ac88a84bf364a48ce5127e5d81a3ad5b。
- 保留完整画面与声音；不得将音频/BGM 的下载当成视频提取成功。
- 保持原账号隔离、平台凭据、增量同步、库操作、播放列表及 Agent 确认执行逻辑。
- 不复用 GetAudio 的数据库、密钥、账号环境、服务端端口或生产默认地址。
- 运行服务端相关测试和 Android 单元测试、APK 构建；真机和外部平台联网验收须有实测证据。
- 飞书写入遵守用户会话约定。GetAudio 的例行发布授权不延伸到 GetVideo；新项目文档须先说明标题、内容、具体归档目录和身份，取得明确确认。优先归档到独立 GetVideo 项目目录。

## Android 发布签名（强制）

- `release` 包**必须**用 `~/.android-keys/getvideo.properties` 指向的固定证书签名，证书 SHA-256 `9D58FEAE…092B7337`（`CN=GetVideo`）。
- 换机器 / 换 Agent 环境首次构建前先跑 `scripts/setup-release-key.sh`（从 `tx:~/.app-keys/` 拉取）。
- `build.gradle.kts` 找不到密钥会**直接构建失败**，这是刻意设计：绝不产出签名不同的 release APK（装了会撞 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。仅在明确不发布时用 `-PallowDebugSignRelease=true` 绕过。
- 密钥文件（`*.jks` / `*.keystore` / `android/keystore.properties`）**绝不入库**。
- 详见 `docs/android-release-signing.md`。
