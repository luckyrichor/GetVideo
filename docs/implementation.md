# GetVideo 0.1.0 实施与验收

生成日期：2026-10-12（北京时间），助手：Codex。
来源：GetAudio@bd4aa630ac88a84bf364a48ce5127e5d81a3ad5b，复制时工作区干净。此文档随 GetVideo 首次实现提交保存；下面的结论对应同一提交中的代码。

## 实现范围

Python / FastAPI / SQLite 服务端与 Android / Compose / Media3 客户端独立复制，改为 `getvideo`、`GETVIDEO_`、`com.getvideo.app` 和 GetVideo 0.1.0。没有复制原数据库、账号、密钥、cookies 或 .env。独立运行端口 8900，未配置 GetAudio 生产服务地址。

B 站指定分 P 原生接口解析，获取视频及独立音频 DASH 流，优先 AVC，然后选择可用最高画质及 AAC 声音；合并为 MP4。抖音只选视频地址，没有画面明确失败；不使用 BGM 替代。服务端在保存前使用 ffprobe 检查视频流及画面尺寸；ffmpeg 流拷贝封装，保留声音或静音画面。

手机选择策略与服务端一致，由 MediaMuxer 复制画面、声音及旋转角度到 MP4，统一时间偏移并按时间戳交错写入，不独立重置声音/画面时间轴。静音视频允许保存。下载完整后原子替换文件，继续执行 SHA256 校验和数据库入库。

播放窗口使用 Media3 PlayerView，连接原共享 MediaSession / 播放服务；剪辑预览增加画面，Transformer 不移除视频轨，导出 H.264/AAC MP4；静音视频的波形为空，但仍可按时间选择与剪辑。播放器画面组件参考 [Android Media3 官方文档](https://developer.android.com/media/media3/ui/surface)。

原有账号、平台绑定/授权、凭据隔离、Agent 确认执行、增量同步、分类、相似候选、库操作、播放列表、循环/随机、定时停止和抓取保留。分类和相似候选沿用元数据与声音规则，不引入图像识别；内部 `audio_*` 和对应 DTO 字段保持兼容，在本项目中表示完整视频文件。新下载路由 `/video/{id}`、`/v2/video/{id}`，旧音频路由是视频下载的兼容别名。

## 实际验证

- 服务端 `python -m pytest tests -q`：**161 passed**，17.45 秒；1 条继承依赖的 Starlette/httpx 弃用警告，不影响测试通过。
- 新增真实 FFmpeg 测试：DASH 画面与声音合并前后的压缩数据 SHA256 一致；合成视频和静音视频均可封装；纯音频文件拒绝保存；保留文件时长；B 站画质/编码与声音选择；抖音无论 BGM 长度都选视频。
- 原服务端回归覆盖账号鉴权、跨账号访问隔离、凭据与平台绑定、Agent、同步、删除、分类、定时抓取、迁移及并发去重。视频下载测试同时核对 `video/mp4`、HTTP Range 206 / Content-Range 和旧路由别名。
- Android `testDebugUnitTest`：**54 tests**，0 failures / errors / skipped；包含新视频候选选路测试以及原队列、同步、库操作等规则回归。
- `assembleDebug`、`assembleRelease`、`assembleDebugAndroidTest` 及 release 必需 lint 均通过；最终构建 34 秒。测试 APK 只验证可编译，没有执行设备仪器测试。
- 独立实际 Uvicorn 进程启动；HTTP `/health` 返回 `{"ok":true}`，OpenAPI 标题/版本为 GetVideo 0.1.0，包含账号视频下载路由。
- 抓取配置 `crawl --check`：6 个任务组有效；CLI 可正常启动。
- APK `aapt` 核对包名 `com.getvideo.app`、versionName `0.1.0`、versionCode `1`、minSdk 26；`apksigner verify` 签名校验通过。
- 安装包为开发交付，release 沿用开发签名；构建产物放在 `artifacts/`，未提交 Git。对应校验值：

```text
624ec1bbe7a34163a63c21ee8ba7fd1204ac8bd482c95ea5b34172e20b82e578  GetVideo-0.1.0-debug.apk
091831e1889652f478e03efca15f039de22fe35ef07709ca1aedc78c94f7db89  GetVideo-0.1.0-release.apk
```

## 尚未验收

当前没有连接 Android 真机，也没有可用模拟器。因此没有声称真机安装、首帧画面、音画同步、视频剪辑硬件编码或外部平台链接下载已实测通过。平台风控、登录状态、付费/地区权限和 Android 设备解码能力仍影响实际提取。新项目没有复制 GetAudio 过去的测试数量或真机验收结论。

本次交付服务端代码、独立运行环境、可安装 APK、systemd / HTTPS nginx 模板和文档；未安装系统服务，未开公网端口，未复用原服务证书或访问秘密。正式服务器应配置独立 HTTPS 地址与凭据，手机设置对应地址；账号登录要求 HTTPS。正式发布前应准备长期 Android 签名。

飞书技术文档应在新 GetVideo 项目目录归档；GetAudio 的例行同步授权不自动扩展到本项目，需另行确认标题、范围、目录及 luochen 用户身份后才能写入。
