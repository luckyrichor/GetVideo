# GetVideo

从 B 站、抖音分享链接下载完整视频，包含独立 Python 服务端和 Android APK。基于 GetAudio `bd4aa630ac88a84bf364a48ce5127e5d81a3ad5b` 的干净工作区创建，版本 **0.1.0**。GetAudio 的账号、平台登录、Agent、同步、分类、播放列表、定时播放、定时抓取、去重、删除、重命名和本地剪辑逻辑继续沿用。

## 视频行为

- B 站：解析指定分 P，下载 DASH 画面和独立声音流，合并为 MP4；优先选择兼容 Android 的 AVC/H.264，以及可访问的最高画质。没有声音轨的稿件可保存为静音视频。
- 抖音：选择作品完整视频地址，保留画面与声音；无视频地址时明确失败，不退回 BGM。
- 服务端使用 FFmpeg 流拷贝封装，不重新压缩画面。手机直提取使用 Android MediaExtractor / MediaMuxer，无损复制画面与声音。下载完成后才替换本地文件。
- APK 通过 Media3 播放画面；开始播放后打开视频窗口，收起后保留播放状态，点底部标题可重新打开。进度、播放队列、循环、随机与定时停止沿用原逻辑。
- 剪辑支持多段选择、画面预览和导出 H.264/AAC MP4；保留视频轨。波形显示的是声音轨，静音视频仍可按时间剪辑。裁剪在需要时重新编码，不宣称剪辑无损。
- 分类仍依据标题与描述，保留 GetAudio 的分类规则；相似候选仍使用 **声音的 Chromaprint**，不能判定画面是否相同，也不自动合并记录。

## 服务端

需要 Python 3.12、FFmpeg / ffprobe。

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
cp .env.example .env  # 按需配置；不必填写模型密钥即可运行规则模式
.venv/bin/uvicorn getvideo.api:app --host 127.0.0.1 --port 8900
```

```bash
.venv/bin/python -m getvideo.cli extract 'https://www.bilibili.com/video/实际BV号/'
.venv/bin/python -m getvideo.cli login --user 自己的用户ID --platform bilibili
.venv/bin/python -m getvideo.cli crawl --check
```

独立配置前缀 `GETVIDEO_`，数据库 `data/getvideo.db`，视频目录 `data/video/`，密钥、cookies、临时文件均在本项目内。未复制 GetAudio 的数据库、用户数据、凭据或环境文件。服务端账号会话头为 `X-GetVideo-Session`。

`deploy/getvideo-api.service` 与 `deploy/getvideo-worker.service` 提供 systemd 模板，默认目录 `/home/ubuntu/project/GetVideo`，API 回环端口 **8900**。`deploy/tx-https/getvideo.conf` 是独立 HTTPS nginx 模板，使用占位域名 `getvideo.example.com`；上线前替换域名、证书路径，并生成独立的设备访问凭据。模板没有直接安装到系统，没有修改现有 GetAudio 服务。

## Android APK

独立包名 **`com.getvideo.app`**、名称 **GetVideo**、图标与版本 **0.1.0 (1)**，可与 GetAudio 同时安装；最低 Android 8.0。

```bash
cd android
# 本地 local.properties 写 sdk.dir=实际AndroidSDK目录
./gradlew testDebugUnitTest assembleDebug assembleRelease --console=plain
```

产物：`android/app/build/outputs/apk/debug/app-debug.apk`、`android/app/build/outputs/apk/release/app-release.apk`。与原项目一样，release 构建目前使用开发签名；它是可安装的开发交付版本，正式分发前应使用独立的长期签名。

手机直提取公开链接不需要服务端。服务器地址可在设置页修改，默认是模拟器地址 `http://10.0.2.2:8900/`；实体手机应填写实际服务器地址，账号登录与用户凭据要求 HTTPS。不会默认连到 GetAudio 的服务器。服务器部署和真机验收另见 [实施与验收](docs/implementation.md)。

## 接口兼容

账号注册、登录、退出、平台绑定、会话鉴权与 `/v2/extract`、`/v2/tracks`、`/v2/sync` 保留。视频下载地址为 `/video/{id}` 和账号隔离的 `/v2/video/{id}`，Content-Type 为 `video/mp4`；沿用框架的文件下载与 Range 支持。

为保持原来的同步、迁移与 DTO 逻辑，内部表和 JSON 继续使用 `Track`、`audio_path`、`audio_format`、`audio_bytes`、`audio_sha256` 等名称，**这些字段在 GetVideo 中指完整视频文件**。旧 `/audio/{id}` 和 `/v2/audio/{id}` 是同一视频下载的兼容别名。GetAudio 与 GetVideo 数据库及应用存储独立，不直接共享。

## 验证

```bash
.venv/bin/python -m pytest tests -q
cd android && ./gradlew testDebugUnitTest assembleDebug assembleRelease --console=plain
```

实际验证数量和范围写入 [实施与验收](docs/implementation.md)。平台风控、登录权限和地区限制仍可能阻止下载；不会伪造成功结果或绕过资源权限。构建及自动测试通过不等于真机或公网部署验收。
