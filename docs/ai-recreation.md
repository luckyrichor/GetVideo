# GetVideo 0.2.0 AI 视频重建

由 Codex 编写，2026-10-18（北京时间）。实现代码版本 GetVideo@cf7a3ff（基于 9996b13）；以下验证对应 cf7a3ff，本次后续提交仅补充交付记录。本文件是实际实现说明；video-recreation-proposal.md 属于设计草案。

## 操作

1. 更新 APK，登录原有 GetVideo 账号，在「AI 重建」上传视频，或从视频库菜单进入。服务器视频也可经 `POST /v2/recreations/from-track/{track_id}` 直接创建素材，避免手机上传。
2. 选择是否分析音轨，然后点击「解析成详细文字」。上传不会自动调用模型。分段结果包括详细画面描述、真实声音记录与不确定信息。
3. 按片段编辑描述及全局补充要求；可恢复原始模型描述。保存时验证 revision，防止旧页面覆盖新编辑。
4. 选择纯文字或首帧参考、分辨率、是否合成原音轨，确认后生成。默认第一段预览、480p。完整模式逐段生成，随后拼接；手机能轮询查看完成段数。
5. 成功后对比原片和结果，导出 MP4，或存入当前账号的视频库并同步下载。原素材独立保留。

当前仅有服务端密钥模式，手机只发送账号会话。APK 不保存 Ark/Tencent Key，也不直接调用厂商；未启用不可调用的腾讯模型和其他 Seedance 型号。

## 实际边界

输入限 MP4/MOV、256 MiB、300 秒，最大 4K 级像素。按时长平均拆为最多约 10 秒的片段，并非自动识别镜头。视觉模型读取降采样视频（最长边 720、2fps）；微小文字、快速动作和精确帧序可能丢失。声音模型辅助记录原音轨，不根据画面编造台词。

生成只使用用户确认的画面描述及连续性信息；首帧模式额外附本段原视频第一帧。提示词约束主体、数量、位置、动作路径、机位、光色、时间映射、禁止新增情节，并保留不确定信息供人工修订。实际模型是 Seedance 1.0 Pro Fast：没有原视频参考能力，无法保证高度一致或跨段稳定；输出声音来自可选的原音轨合成，不是声音生成。

运行提示词位于 getvideo/prompts/ 和 recreation_provider.generation_prompt。保留模型原描述、当前编辑版 revision，以及每次生成的不可变文字快照；尚未提供所有中间编辑版本的完整恢复界面。生成记录保留实际模型、厂商任务 ID、用量和结果哈希；外部任务 ID 和带签名的下载 URL 不返回手机。

## 服务端

API 路由 `/v2/recreations` 全部使用现有 `X-GetVideo-Session` 鉴权，按 owner_id 隔离。可选 `GETVIDEO_RECREATION_ALLOWED_USERS` 指定 user_id 白名单；留空允许已登录账号使用。手机使用账号会话，不使用 nginx Basic 或 Ark Key。

持久表为 recreation_projects、recreation_clips、recreation_runs、recreation_run_clips，素材及中间文件位于 data/recreation（忽略 Git）。新增表为增量迁移；旧视频、账号及播放列表不改变。素材尚无自动过期清理，请监控磁盘；不要删除仍在处理的目录。

API 和 AI worker 通过 systemd EnvironmentFile 引用当前机器共享 Ark 配置；不用复制真实密钥进代码或环境示例。独立 worker 持有文件锁，重启后恢复分析和已知厂商任务轮询。付费 POST 前提交状态；响应不确定或进程中断时标记 submission_unknown，不自动重新 POST。GET/下载网络失败重试已有任务；明确失败、过期或不可访问时提示核对。取消只停止尚未提交片段。

nginx 的精确 `/v2/recreations/upload` 路由允许 256 MiB 流式上传，其余已有请求大小限制不扩大。生成结果从厂商允许的 HTTPS 域名下载，跳转逐次校验，不附带 Ark Authorization。媒体处理限制本地 MP4/MOV；保存与下载校验 SHA-256。

认证命令行：

```bash
# GETVIDEO_SESSION 已从自己的账号登录获取，不要把会话或模型密钥写入公共命令记录
.venv/bin/python -m getvideo.recreation_cli upload ./sample.mp4
.venv/bin/python -m getvideo.recreation_cli list
.venv/bin/python -m getvideo.recreation_cli analyze PROJECT_ID
.venv/bin/python -m getvideo.recreation_cli status PROJECT_ID
.venv/bin/python -m getvideo.recreation_cli generate PROJECT_ID --revision 1 --request-key UNIQUE_REQUEST_001
.venv/bin/python -m getvideo.recreation_cli download PROJECT_ID RUN_ID ./generated.mp4
```

默认连接回环 8900；远程传 `--server https://43.142.37.199:8443`。默认只生成第一段；完整生成传 `--full`，首帧传 `--mode first_frame`。编辑描述使用 PUT description API 或手机界面。

## 验证与限制

已用独立临时数据库及自造 4 秒测试视频走通真实 Ark：上传、Pro 画面分析、Mini 声音分析、文字生成、异步轮询、下载、音轨合成、哈希及视频库入库。输出 4 秒，367281 字节；生成用量 39285 tokens，存在实际平台用量，不宣称免费。

首帧请求结构、原文完整传输及失败路径通过适配器测试；真实生成验收使用纯文字 480p，未对所有分辨率进行付费穷举。手机验证包含协议测试和 APK 构建；当前机器没有连接 Android 真机，未声称触屏实测。


## 交付验证记录（2026-10-18，北京时间）

验证代码版本 `GetVideo@cf7a3ff`：

- 服务端完整回归：185 passed，13.70 秒，1 条既有 Starlette/httpx 弃用警告。
- Android：62 项单元测试，0 失败；`testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest --offline --console=plain` 全部成功。最终增量构建耗时 31 秒；仪器测试包已编译，未连接真机执行。
- APK：`com.getvideo.app`，versionName 0.2.0，versionCode 4，minSdk 26 / targetSdk 34。release 仍用现有开发签名，证书 SHA-256 `25302366b97b6ccc3fb7e0e6e4df642f809fe2cf401c485ca2ed2ee27960dc57`，与原 0.1.0 安装包一致。
- TX 已安装并启动独立 API / AI worker，worker 无重启。HTTPS 健康检查返回 GetVideo 0.2.0；未登录访问 AI 接口返回 401。临时自检账号验证模型配置、超过 2 MiB 的真实 HTTPS 视频上传、私有素材 Range 206；未触发模型调用，自检账号、会话及素材已删除。
- 原 GetAudio 8800 健康检查正常；配置和服务未更改。升级前已备份原代码、数据库、项目环境及 GetVideo 服务/nginx 配置到本机独立权限目录。
- 实际共享 Ark/Tencent 密钥扫描已通过；Git 中没有凭据或模型媒体 payload。生成结果及 APK 位于忽略 Git 的 artifacts/；安装包另通过飞书文件交付。

安装包 SHA-256：

```text
b869f58d62ad02a750f462b6f26e1da8c6f3898f56b1cb3e91e8dc91851b71e6  artifacts/GetVideo-0.2.0-debug.apk
3d0916736dedc44402ae4193d87ca326a04f380aece225a551c01d20c991c1da  artifacts/GetVideo-0.2.0-release.apk
```
