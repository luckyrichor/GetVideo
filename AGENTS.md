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

## 项目交接（2026-10-27）

### 项目与技术栈

GetVideo 是独立的完整视频提取、播放与 AI 重建工具，包含 Python 3.12 服务端和 Android App。来源 GetAudio，但包名、数据、端口和平台凭据隔离。保留 B站/抖音提取、账号、平台授权、分类、同步、播放列表、本地剪辑；AI 功能提供视频分镜描述、可编辑文字、文字/首帧参考生成及入库。

服务端使用 FastAPI、SQLAlchemy/SQLite、httpx、FFmpeg/ffprobe；Android 使用 Kotlin、Compose、Room（当前 schema 8）、Retrofit/OkHttp、Media3。最低 Android 8.0，compile/target SDK 34、Java 17。具体依赖版本以 requirements.txt 与 android/ 下 Gradle 文件为准。

### 启动、测试、构建

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
# 新环境按 .env.example 配置，不覆盖已有生产 .env
.venv/bin/uvicorn getvideo.api:app --host 127.0.0.1 --port 8900
.venv/bin/python -m pytest -q
cd android
# 本机 SDK /opt/android-sdk，配置在忽略 Git 的 local.properties
./gradlew testDebugUnitTest assembleRelease --offline --console=plain
```

AI worker：在已配置服务器 Ark 凭据的环境运行 `.venv/bin/python -m getvideo.recreation_worker`。变量 ARK_API_KEY 为服务端密钥，GETVIDEO_RECREATION_* 控制模型和上传限制；密钥不得写进 APK。GETVIDEO_DATABASE_URL、GETVIDEO_AUDIO_DIR 等配置见 getvideo/config.py。共享环境包装器是否可用，执行前应按本机配置检查。

### 目录与核心流程

- getvideo/api.py：HTTP 入口；accounts.py / account_api.py：会话和账号隔离接口；db.py / models.py：数据库与同步。
- getvideo/recreation_api.py、recreation_worker.py、recreation_provider.py、recreation_media.py：素材上传、持久任务、Ark 请求和媒体处理；recreation_models.py：四张任务表。
- android/app/src/main/java/com/getvideo/app/：MainActivity 入口；data/localextract/ 为直提取及批量链接识别，data/repo/ 为账号、同步、背景配置，ui/ 为界面，player/ 为播放。
- deploy/：GetVideo API、AI worker、定时抓取 worker 的 systemd 模板及独立 nginx HTTPS 配置。
- docs/ai-recreation.md：0.2.0 AI 能力边界；docs/batch-extraction-and-background-0.3.0.md：0.3.0 功能与已知实现细节；旧文档的版本和真机结论不能直接套用到新提交。
- artifacts/：忽略 Git 的安装包、校验文件及临时生成媒体；data/recreation/：私有 AI 素材和结果，不提交。

手机直提取不经过服务端；服务端功能通过 X-GetVideo-Session 鉴权。AI 解析与生成经账号隔离接口入库，由单独 worker 执行。付费生成 POST 在提交结果不确定时不自动重复提交，避免重复扣费。实际生成模型可用范围以账号实测为准，不能凭模型目录承诺已开通。

### 当前进度与本次交付

- 2026-10-28 完成 0.3.9（versionCode 16）：与 GetAudio 0.9.9 同步，只改拖动。① `itemsIndexed` **不再传 key**：带 key 时 `LazyColumn` 换位会把「第一个可见项」按 key 钉回原像素，拖第一条时第一个可见项正是被拖行，于是每换一格列表自己滚一行，表现为「原位置空掉、拖到 7、8 之间还停在最上面、留一大段透明空白」。② 加**反向换位死区**：相邻行高不同（两行/三行标题），换位后布局重排，手指落在重叠区会每帧来回换（抖动），现在换回去要再走 `0.5 × min(被拖行高, 目标行高)`。③ 删掉 `residual`，判定直接用手指视口坐标 `dropTarget(from, pointerY, slots, inset)`；`beginDrag(index)` 改按下标命中。`ReorderableQueue` 同改。验证：83 项单测（14 项拖动判定，`AnchoredSim` → `StableSim`）、Lint 0 errors / 25 warnings、debug+release 构建成功，产物 0.3.9 (16)。GetVideo 未装真机；同源 GetAudio 0.9.9 已在真机上验过下拖、上拖到第一位、再拖回和重启持久化。
- 2026-10-28 完成 0.3.8（versionCode 15）：与 GetAudio 0.9.8 同步。① **库页手动排序拖动**（`LibraryScreenV2.LibraryTrackList`）改用与队列相同的模型：被拖行移出列表画浮层、原位留等高 `Spacer`，落点 `dropTarget(from, 槽位中心 + residual, slots, inset)`，换位后只保留「手指落在新一行里的偏移」，修「第一条往下拖立刻跳到最后」；同时把检测器从每行 `pointerInput` 挪到 `LazyColumn`（行被换成 `Spacer` 时节点会销毁，拖动第一次换位后就断）。② **下载失败自动上报**：`TrackRepository.download()` 失败时把阶段、HTTP、已收字节、期望字节、耗时、主机、异常写入 `failure-outbox` 并唤醒 `FailureReportWorker`，同视频同错误 10 分钟内只报一次。③ **媒体下载抗断流**：`DirectExtractor.download()` 每地址最多 3 次（仅 `IOException`，400/800ms 退避），带 `Connection: close` + `Accept-Encoding: identity`；`ApiProvider.humanMessage` 补 `SocketException / EOFException / SSLException` 中文映射。验证：82 项单测（11 项拖动判定）、Lint 0 errors / 25 warnings、debug+release 构建成功，产物 0.3.8 (15)。GetAudio 侧已真机验证首条下拖只走一格且重启后顺序保持；GetVideo 未装真机复验。
- 2026-10-28 完成 0.3.7（versionCode 14）：0.3.6 的拖动仍被用户判为坏的（首条下拖瞬间到 26–30 条、第 26 条上拖疯狂抖动且推不上去、拖着拖着停下）。根因两条：被拖行留在列表里靠 `translationY` 补偿，补偿按上一帧槽位算，换位那一帧必然错位 → 抖动；中心被夹在可视区后判定退化成「见谁换谁」→ 一帧一格连换。现在被拖行移出列表画在浮层（原位放等高 `Spacer` 占位），落点改为「手指所在行」且只从 `visibleItemsInfo` 里选（`dropTarget()` 纯函数，8 项单测），贴边滚动只滚列表不回写手指。同时外观页新增「显示区域 → 调整显示区域」：9:19.5 选框 + 旋转 90° + 双指缩放 + 单指平移 + 整张/铺满/复位，几何在 `data/repo/WallpaperFrame.kt`，每张背景单独记忆；静态壁纸由长边 1280 重出为 2200。详见 docs/queue-drag-and-motion-background.md。
- 2026-10-28 完成 0.3.6（versionCode 13）：拖动返工 —— 0.3.5 把贴边滚动的像素补进被拖行中心，首条下拖会窜到末条、末条上拖剧烈抖动；现在中心只跟手指走、判定夹在可视区内、只和紧邻上下行比且一次只挪一格，判定抽成纯函数 `reorderStep()` 并补 6 项单测。同时内置 13 张静态壁纸 + 3 张动图 + 3 段视频（全部静音，随 APK 打包），外观页两排缩略图可点选。详见 docs/queue-drag-and-motion-background.md。
- 2026-10-28 完成 0.3.5（versionCode 12）：播放列表去掉行首拖动把手，改为长按整行拖动；重写落点判定与贴边自动滚动，修「第一个条目往下拖会抖」和「拖出视口后停止重排」；外观页新增动态背景，支持 mp4/webm 视频与 GIF/WebP 动图，静音循环、不抢音频焦点、跟随生命周期暂停。详见 docs/queue-drag-and-motion-background.md。
- 验证（0.3.9）：83 项 Android 单测通过（14 项拖动判定），Lint 0 errors / 25 warnings，debug/release 构建成功，产物为 0.3.9 (16)。GetVideo 本轮未装真机；同源 GetAudio 0.9.9 已在 1440×3200 / Android 14 上验证：首条下拖占位行贴着手指、顶部不空、序号连续；第 5 条拖到第一位正常；再拖回第 4 位无空白；重启进程后顺序保持。本轮未改服务端，未部署。
- 验证（0.3.8）：82 项 Android 单测通过（11 项拖动判定），Lint 0 errors / 25 warnings，debug/release 构建成功，产物为 0.3.8 (15)。GetVideo 本轮未装真机；同源的 GetAudio 0.9.8 已在 1440×3200 / Android 14 上验证：库页首条下拖只移动一格、进程重启后顺序保持。本轮未改服务端，未部署。
- 验证（0.3.7）：79 项 Android 单测通过（8 项拖动判定 + 9 项取景几何），Lint 0 errors / 26 warnings，debug/release 构建成功，产物为 0.3.7 (14)。真机 UI 只在 0.3.6 时期验过（两排缩略图正常、视频与动画 WebP 可播放）；0.3.7 交付时手机锁屏，拖动与取景页未做真机复验。本轮未改服务端，未部署。
- 2026-10-27 已从 origin/main 快进同步至 GetVideo@27323ae；业务代码未自行改写，没有提交或推送新代码。Android 回归发现 TrackEntity 新增 coverUrl 后两个旧测试夹具的位置参数错位，已在 SyncMergeTest.kt / QueueRulesTest.kt 改为命名参数，测试修正尚未提交。
- Android 当前版本为 0.3.0（versionCode 7）；新增批量提取、自定义背景、只看背景及卡片不透明度调节来自已有仓库提交。本轮只同步、验证、构建和分发。
- 最新提交支持 `-PgetvideoPreview=true` 构建可并存的 `.preview` 包，名称「GetVideo 新版」。用户本轮要求最新 APK，默认交付普通 `com.getvideo.app` 覆盖升级包，不自动改为预览版。
- 服务端完整回归：27323ae 对应 185 项通过，16.45 秒，1 条既有 Starlette/httpx 弃用警告。
- Android 首次完整构建因两个测试夹具的位置参数与新增 coverUrl 不兼容而失败；已只修正测试构造参数并重跑。27323ae + 未提交的两处测试夹具修正：62 项 Android 单测通过，release 构建成功（28 秒）。应用业务代码仍完全对应 27323ae。当前没有连接真机，不声称设备验收。
- 本轮安装包 artifacts/GetVideo-0.3.0-release.apk：包名 com.getvideo.app、版本 0.3.0 (7)，签名与 0.2.0 相同；SHA-256 见 artifacts/SHA256SUMS-0.3.0。已通过 codex profile 的机器人身份将 APK 发到当前群，消息 ID om_x100b613ccff704a8cf6e0e5b9800b44；这仅为文件交付，尚未写入个人知识库。
- 原 GetVideo API 与 AI worker 服务均为 active；本轮未重启或更新生产服务。

### 决策、限制与下一步

1. 保持现有签名和包名以支持升级；release 仍采用已有开发签名，不将其描述为正式商店签名。每次交付需核对 APK 的版本、包名、签名和 SHA-256。
2. 原有 README 的顶部版本和 Android 版本说明落后于 Gradle；取实际 Gradle 和 APK 元数据为准。
3. 下次先完成 30 条真实队列下的长按拖动验收（首行下拖、贴边上下滚、快速甩动）、动态背景与音频共存、切后台/锁屏恢复、账号切换；当前不能据本机自动测试宣称真机无问题。
4. 本次仅为 APK 同步构建；需要更新生产服务时另按对应任务执行，不因拉代码自动重启服务。
5. 后续功能开发按任务范围进行，保留他人改动；不要把文档中的旧模型访问结论当作当前开通状态。

### 飞书归档与待同步

已只读核对 codex profile 的用户身份可访问「我的文档库」。已有项目目录 GetVideo，节点 P79zwhJO2iroF5kkdLscarppnrc；目录下已有「GetVideo 技术与部署说明（0.1.2）」节点 Av5awQEjTi72jJk6ULmcSjV9nJc。未修改旧文档。

飞书待同步：2026-10-27，GetVideo@27323ae 最新代码同步、测试、APK 构建与交付记录。本会话尚未取得首次发布此范围的明确确认，需先说明标题、目录和 codex 用户身份。拟复用「我的文档库/GetVideo」，新建「GetVideo 项目概览」「GetVideo 开发记录」，不新增目录层级；未经确认不写入。文档链接在实际创建并回查后补充，不编造链接。

### 更新日志

- 2026-10-28：0.3.9（code 16）。拖动第三次修正：去掉 `itemsIndexed` 的 key（避免 `LazyColumn` 按 key 锚定把列表自己滚走），加反向换位死区（避免行高不等导致的重叠区来回换），判定回到手指视口坐标并删掉 `residual`。83 项单测、Lint 0 errors 通过。

- 2026-10-28：0.3.8（code 15）。库页手动排序拖动改为与队列同一套（浮层 + 等高占位 + `residual` 记账，手势挂到 `LazyColumn`），修首条下拖跳到最后与拖动中断；下载失败接入失败报告队列并唤醒上传，媒体下载按地址重试 3 次并记已收字节。82 项单测、Lint 0 errors 通过。

- 2026-10-28：0.3.7（code 14）。拖动第三次返工：被拖行改走浮层、落点改为「手指所在行」且只从可视行里选，修首条下拖窜到 26–30 条与上拖抖动；新增壁纸「调整显示区域」（旋转/缩放/选框）；静态壁纸重出 2200 长边。79 项单测、Lint 0 errors 通过。

- 2026-10-28：0.3.6（code 13）。拖动返工（中心不再叠加滚动量、一次一格、夹住可视区），补 6 项判定单测；内置 13 静态 + 3 动图 + 3 视频壁纸，全部静音。68 项单测、Lint 0 errors 通过，debug/release 构建并覆盖安装到真机。未改服务端，未执行 30 条队列的手势验收，未同步飞书。

- 2026-10-28：0.3.5（code 12）。队列去掉行首把手、长按整行拖动；修首个条目下拖抖动与拖出视口停止重排；新增视频/动图动态背景。62 项 Android 单测、Lint 0 errors 通过，debug/release 构建并覆盖安装到真机。

- 2026-10-27：Codex 同步 GitHub@27323ae，完成 0.3.0 APK 构建、185 项服务端回归、62 项 Android 单测，修正两个旧测试夹具，补全本项目交接文档；CLAUDE.md 引用唯一正文 AGENTS.md。未发布飞书文档，未改生产服务。
