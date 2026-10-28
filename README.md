# GetVideo

从 B 站、抖音分享链接下载完整视频，包含独立 Python 服务端和 Android APK。基于 GetAudio `bd4aa630ac88a84bf364a48ce5127e5d81a3ad5b` 的干净工作区创建，Android 当前版本 **0.3.8**（versionCode 15）。GetAudio 的账号、平台登录、Agent、同步、分类、播放列表、定时播放、定时抓取、去重、删除、重命名和本地剪辑逻辑继续沿用。

视频库「手动」排序与播放队列都已支持长按整行拖动排序（同一套浮层 + `residual` 落位模型，首条下拖只走一格）；库内下载失败会自动进失败报告队列并上传，媒体下载按地址重试 3 次以抗 CDN 断流。Android 播放列表现已支持长按整行拖动排序（行首把手已移除，被拖行走浮层、落点取手指所在行），外观页支持 mp4/webm 视频与 GIF/WebP 动图作为循环背景，并新增「调整显示区域」（旋转 90° / 双指缩放 / 单指平移 / 整张或铺满）来给 16:9 横图选竖屏要露的那一块，详见 [队列拖动与动态背景](docs/queue-drag-and-motion-background.md)。

## 视频行为

- B 站：解析指定分 P，下载 DASH 画面和独立声音流，合并为 MP4；优先选择兼容 Android 的 AVC/H.264，以及可访问的最高画质。没有声音轨的稿件可保存为静音视频。
- 抖音：选择作品完整视频地址，保留画面与声音；分享页的平台 `/playwm/` 水印地址在已识别的抖音域名上改取对应 `/play/` 播放流，播放源优先于下载备用源；无视频地址时明确失败，不退回 BGM。源画面自带的文字、标志仍保留，不能保证每个作品都有无水印源。
- 服务端使用 FFmpeg 流拷贝封装，不重新压缩画面。手机直提取使用 Android MediaExtractor / MediaMuxer，无损复制画面与声音。下载完成后才替换本地文件。
- APK 通过 Media3 播放画面；开始播放后在视频库页面内展示画面，按原比例完整显示；全屏按钮进入沉浸播放，横屏视频横向展示，退出后保留播放进度，收起后点底部标题可重新打开。进度、播放队列、循环、随机与定时停止沿用原逻辑。
- 剪辑支持多段选择、画面预览和导出 H.264/AAC MP4；保留视频轨。波形显示的是声音轨，静音视频仍可按时间剪辑。裁剪在需要时重新编码，不宣称剪辑无损。
- 分类仍依据标题与描述，保留 GetAudio 的分类规则；相似候选仍使用 **声音的 Chromaprint**，不能判定画面是否相同，也不自动合并记录。

## AI 视频描述与重建（0.2.0）

手机底部打开「AI 重建」，或在视频库的视频菜单选择「AI 描述与生成」。流程：上传原视频 → 点击解析 → 检查/编辑每段详细分镜 → 确认生成 → 预览、导出或保存到视频库。使用现有 GetVideo 账号及服务器上的 Ark Key；APK 不包含模型密钥。上传 MP4/MOV 上限 256 MB / 300 秒。已提交任务在服务器持久运行，退出手机页面后可以恢复查看。

- 画面分析使用 `doubao-seed-2-1-pro-260915`，另用 `doubao-seed-2-0-mini-260428` 可选分析真实音轨。描述覆盖主体外观、布局、动作时间、光色、运镜、可见文字及不确定信息；保留模型原文供恢复。
- 视频生成使用当前账号已实测可用的 `doubao-seedance-1-0-pro-fast-251015`。支持纯文字、文字加每段首帧参考，以及 480p/720p/1080p。默认先生成第一段预览；取消预览选项后逐段生成并合成完整视频。
- 当前按时间平均分段，每段最多约 10 秒；画面采样 2fps，时间定位约 0.5 秒。没有镜头边界检测或逐帧复刻保证，跨段人物、细节与动作连续性可能变化。首帧参考也不等于视频参考。
- 生成模型不生成声音。可将原音轨合成到新画面，或输出无声视频。素材和生成结果使用账号鉴权下载，不向模型发送 GetVideo 会话；视频与首帧直接通过模型请求传输。
- 解析和生成都按模型平台实际用量计费。提交重试复用请求标识；提交响应不确定时不会自动重新生成，必须先核对平台记录。停止任务只阻止后续片段，不能撤销已产生用量。

服务端启动 API 后，还需独立运行 AI worker：

```bash
# 使用当前机器已授权的 Ark 环境；服务方式见 deploy/getvideo-ai-worker.service
with-ark .venv/bin/python -m getvideo.recreation_worker
```

认证 API 位于 `/v2/recreations`，客户端也可用 `.venv/bin/python -m getvideo.recreation_cli --help`；`GETVIDEO_SESSION` 是自己的 GetVideo 登录会话，不是模型 API Key。生成前须传当前描述 `revision` 和独立 `request_key`；下载、保存视频均按账号隔离。完整操作和部署说明见 [AI 重建说明](docs/ai-recreation.md)。

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
