# GetVideo HTTPS 项目路径迁移实施计划

**目标**：公网入口改为 `https://43.142.37.199/getvideo/`；先双入口，手机全链路实测通过后才删除 8443。
**依据**：用户在本会话确认的六项迁移方案；本轮已授权开发、部署及真机验收。
**架构**：443 现有 nginx server 增加独立 location，剥离 `/getvideo` 后转发 127.0.0.1:8900。内部 API 路径不改；手机统一以服务基础路径拼接资源。旧公网入口迁移期间保留。
**技术栈**：nginx、FastAPI/ASGI、Kotlin/Compose、Retrofit/OkHttp、Room。

## 全局约束
- 不清库、不卸载手机 App、不改签名、不改其他项目接口。
- 仅迁移确定的官方旧入口；自定义服务器、显式本机模式保持。
- 账号会话和云端数据归属需要延续；不同项目不共享会话。
- 无法实测的必验功能属于下线阻塞项；不得关闭 8443。
- 当前目录干净，独立开发分支 `feat/https-project-path`；本轮在现有目录执行，避免额外工作目录影响构建和设备调试。

## Review Focus
- 地址带前缀时的 `/`、`..`、编码路径、跨项目重定向不得泄漏凭据。
- 升级中断、重复启动不改变云端记录 ID、游标或本地文件。
- 旧失败报告、Agent 回执、AI 缓存沿用稳定身份。
- 新前缀必须优先于 GetAudio 正则 location；旧接口 Basic 保护保留。
- 无登录 Cookie、平台授权未配置或手机未确认均不可记为验收通过。

## Task 1：手机路径与身份迁移
文件：ServerConnectionPolicy.kt、ServerProbe.kt、AccountScope.kt、AccountStore.kt、Settings.kt、ApiProvider.kt、AgentOutbox.kt、FailureOutbox.kt、TrackRepository.kt、LoginRepository.kt、RecreationRepository.kt。
- [x] 测试先行：官方地址迁移、自定义保留、前缀健康检查、资源拼接和凭据路径隔离；运行并观察失败。
- [x] 实现统一地址解析。云端 scope 对新旧官方入口使用历史身份，保持已有 Room 行/游标/缓存；加密账号文件采用原子写。
- [x] 更新下载/恢复/二维码/AI 资源链接；会话只能发给相同 HTTPS origin 且位于基础路径内的请求。
- [x] 全部 Android 单测、Lint、debug/release 构建；版本 0.3.11 (18)，固定证书。

## Task 2：服务端路径代理与部署
文件：getvideo/public_path.py、getvideo/api.py、deploy/tx-https/getvideo-path.conf、部署说明、tests/test_public_path.py。
- [x] 测试先行：新前缀的 ASGI root_path、重定向/OpenAPI、旧路径并行可用。
- [x] 仅信任本机代理注入的前缀，nginx 覆盖客户端前缀头；回调日志脱敏。
- [x] 准备配置备份、SQLite 一致性快照；新增独立 snippet 并在现有 443 server include。
- [x] nginx -t、reload；比对 GetAudio/其他路由基线，公网 TLS 与新旧入口检查。

## Task 3：升级及验收
- [x] 覆盖安装前核对手机包名、版本、签名；不读取/输出密码或 token。
- [x] 原会话迁移、注册/登录、同步、服务器提取、手机提取、播放、下载、B站授权、AI 资源通过；用户确认原本未配置的抖音 OAuth 另行增加，不阻塞本次下线。
- [x] 未通过则修复；不能验证则保留旧入口并记录原因。
- [x] 按用户最终确认关闭 8443 和专属续期；用户删除云防火墙规则，外部复核不可连接。
- [x] 更新 AGENTS.md、飞书 GetVideo 开发记录及验收/回滚说明。

## 执行记录
- 2026-10-31 用户要求暂停：新路径已上线，旧入口完整保留；当前停止继续实施。最新事实与恢复顺序见 AGENTS.md 顶部暂停交接。
- GitHub 同步：2026-10-31，main=3dccb1c，无远端新提交；TX=b4d80ef，无未提交改动。
- Python 基线：206 passed / 1 failed，失败为 test_real_fingerprint_ignores_metadata，本机 ffmpeg 指纹能力待核实；与本轮代码无关。
- 手机：ADB 6c3619c，com.getvideo.app 0.3.10 (17)。

- 2026-10-31 恢复后交付：91 项 Android 单测、Lint 0 errors / 25 warnings；TX 210 项通过；两份真机验收 PASS、用户完成 B站扫码确认。测试 fixture 已定向清理，飞书已追加并回查。抖音 OAuth 不可验证，保留 8443，不声称最终下线完成。

- 2026-10-31 最终决策与执行：用户明确取消将原本未配置的抖音 OAuth 作为下线前提。旧 8443 nginx 站点已归档关闭，专属续期停用，用户确认删除云防火墙规则；服务器无监听、外部连接失败，新路径正常。入口迁移完成，抖音 OAuth 后续在新路径下增加。

- 2026-10-31 用户追加要求：推送 main 并发布 v0.3.11，附固定签名 APK 与校验文件。旧模板删除后发现部署测试仍引用旧文件，已更新为验证新前缀的权限白名单，再跑完整回归。
