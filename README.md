# GetVideo

从 B 站、抖音分享链接下载完整视频，包含独立 Python 服务端和 Android APK。基于 GetAudio `bd4aa630ac88a84bf364a48ce5127e5d81a3ad5b` 的干净工作区创建，版本 **0.1.2**。GetAudio 的账号、平台登录、Agent、同步、分类、播放列表、定时播放、定时抓取、去重、删除、重命名和本地剪辑逻辑继续沿用。

## 视频行为

- B 站：解析指定分 P，下载 DASH 画面和独立声音流，合并为 MP4；优先选择兼容 Android 的 AVC/H.264，以及可访问的最高画质。没有声音轨的稿件可保存为静音视频。
- 抖音：选择作品完整视频地址，保留画面与声音；分享页的平台 `/playwm/` 水印地址在已识别的抖音域名上改取对应 `/play/` 播放流，播放源优先于下载备用源；无视频地址时明确失败，不退回 BGM。源画面自带的文字、标志仍保留，不能保证每个作品都有无水印源。
- 服务端使用 FFmpeg 流拷贝封装，不重新压缩画面。手机直提取使用 Android MediaExtractor / MediaMuxer，无损复制画面与声音。下载完成后才替换本地文件。
- APK 通过 Media3 播放画面；开始播放后在视频库页面内展示画面，按原比例完整显示；全屏按钮进入沉浸播放，横屏视频横向展示，退出后保留播放进度，收起后点底部标题可重新打开。进度、播放队列、循环、随机与定时停止沿用原逻辑。
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

`deploy/getvideo-api.service` 与 `deploy/getvideo-worker.service` 提供 systemd 模板，默认目录 `/home/ubuntu/project/GetVideo`，API 回环端口 **8900**。TX 已启用独立 GetVideo API 与 HTTPS `8443` 入口，nginx 配置见 `deploy/tx-https/getvideo.conf`，独立证书及自动续期见 [部署记录](deploy/tx-https/README.md)。GetAudio 的 `8800/443` 入口保持独立。

## Android APK

独立包名 **`com.getvideo.app`**、名称 **GetVideo**、图标与版本 **0.1.2 (3)**，可与 GetAudio 同时安装；最低 Android 8.0。

```bash
cd android
# 本地 local.properties 写 sdk.dir=实际AndroidSDK目录
./gradlew testDebugUnitTest assembleDebug assembleRelease --console=plain
```

产物：`android/app/build/outputs/apk/debug/app-debug.apk`、`android/app/build/outputs/apk/release/app-release.apk`。与原项目一样，release 构建目前使用开发签名；它是可安装的开发交付版本，正式分发前应使用独立的长期签名。

手机直提取公开链接不需要服务端。服务器地址可在设置页修改，默认服务器为 `https://43.142.37.199:8443/`，旧模拟器默认地址自动迁移；用户自定义地址及明确选择的本机模式保留。连接测试最多等待 10 秒、可取消，并校验服务身份，避免误连 GetAudio。账号与平台凭据在 GetVideo 中独立管理，首次需单独注册。不会默认连到 GetAudio 的服务器。服务器部署和真机验收另见 [实施与验收](docs/implementation.md)。

## 接口兼容

账号注册、登录、退出、平台绑定、会话鉴权与 `/v2/extract`、`/v2/tracks`、`/v2/sync` 保留。视频下载地址为 `/video/{id}` 和账号隔离的 `/v2/video/{id}`，Content-Type 为 `video/mp4`；沿用框架的文件下载与 Range 支持。

为保持原来的同步、迁移与 DTO 逻辑，内部表和 JSON 继续使用 `Track`、`audio_path`、`audio_format`、`audio_bytes`、`audio_sha256` 等名称，**这些字段在 GetVideo 中指完整视频文件**。旧 `/audio/{id}` 和 `/v2/audio/{id}` 是同一视频下载的兼容别名。GetAudio 与 GetVideo 数据库及应用存储独立，不直接共享。

## 验证

```bash
.venv/bin/python -m pytest tests -q
cd android && ./gradlew testDebugUnitTest assembleDebug assembleRelease --console=plain
```

实际验证数量和范围写入 [实施与验收](docs/implementation.md)。平台风控、登录权限和地区限制仍可能阻止下载；不会伪造成功结果或绕过资源权限。构建及自动测试通过不等于真机或公网部署验收。
