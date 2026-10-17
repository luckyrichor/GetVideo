# TX GetVideo HTTPS 部署记录

执行：Codex，2026-10-17。来源：GetVideo@a87a9ed，加本次工作区修复与部署修改；当前发布版本 0.1.2。部署源码一致性以 `artifacts/source-manifest-0.1.2.sha256` 核验，不将未提交内容冒充原始提交。

## 实际部署

- 手机根地址：`https://43.142.37.199:8443/`。腾讯云轻量实例 `lhins-6h8y270u`，上海，控制台名称 `lc`。经用户明确确认，新增 IPv4 TCP 8443 允许规则，备注 `GetVideo HTTPS 手机直连`。
- `getvideo-api.service` 开机自启，工作目录 `/home/ubuntu/project/GetVideo`，监听 `127.0.0.1:8900`。只启用 API，不启用定时抓取 worker。
- nginx 站点 `/etc/nginx/sites-available/getvideo`，启用链接位于 `sites-enabled/`。新增监听 8443；配置通过 `nginx -t` 后 reload。
- 数据库、视频目录与环境均在 GetVideo 项目目录。独立 AES 密钥位于权限 600 的 `.env`，由 systemd EnvironmentFile 注入，不写入源码、APK 或日志。未复制 GetAudio 账号、平台凭据或数据。
- 账号、新版视频和 Agent 接口使用 `X-GetVideo-Session`；未登录 `/account/me` 返回 401。旧版设备接口由独立 `/etc/nginx/getvideo.htpasswd` 封闭，未向手机分发共享 Basic 口令。
- TLS 为单独申请的 Let's Encrypt IP 证书；独立 ACME 账号、配置和私钥目录 `/etc/getvideo-letsencrypt/`。证书在 `live/getvideo-tx/`，此次有效期至 2026-10-24 08:13:15 UTC。没有复用 GetAudio TLS 私钥。
- HTTP-01 验证只使用已有 HTTP 80 的 ACME webroot `/var/lib/getaudio-acme` 临时放置挑战文件，不接管或重写 GetAudio 站点。
- 独立续期服务 `getvideo-cert-renew.service`、timer 每日两次检查；使用独立工作与日志目录。续期后先检查 nginx 配置，再 reload。短期 IP 证书必须保持续期任务运行。[官方说明](https://letsencrypt.org/2026/03/11/shorter-certs-certbot)

## 验证与边界

- GetVideo `/health` 返回 `ok=true, app=GetVideo, version=0.1.2`；Android 检查服务标识，不能将 GetAudio 健康响应误认作 GetVideo。
- 手机已同包同签名升级 0.1.2 (3)，地址检查并保存成功，显示“服务器连接正常，请登录 GetVideo 账号”。ADB reverse 为空；nginx 收到手机公网来源、User-Agent `okhttp/4.12.0` 的健康请求。
- 服务端完整测试 163 项通过，8.88 秒；Android 58 项通过，debug/release 构建通过。保留 1 条 Starlette/httpx 弃用警告。
- 独立证书 `certbot renew --dry-run` 已实际通过，全部模拟续期成功；timer 已开机启用。
- 原 GetAudio API 与公网 HTTPS 健康检查均正常。
- GetVideo 账号独立，未代用户注册账号或复制 GetAudio 会话。B站扫码能力已返回可用，但绑定成功仍需用户登录并扫码；抖音 OAuth 因独立开放平台应用参数未配置，接口明确显示暂不可用。公开抖音视频的手机直提取不依赖 OAuth。
- 未配置新的大模型 API 凭据，本次只部署连接和已有服务功能；AI 可用范围不冒充已验收。

## 运维与回滚

检查：`systemctl status getvideo-api getvideo-cert-renew.timer`；日志：`journalctl -u getvideo-api`。续期演练：`sudo /opt/getaudio-certbot/bin/certbot renew --dry-run --config-dir /etc/getvideo-letsencrypt --work-dir /var/lib/getvideo-certbot --logs-dir /var/log/getvideo-certbot`。

同步前一致 SQLite 快照和源码归档：`/home/ubuntu/project/GetVideo-backup-20261017T171204Z/`。后续修改前继续备份数据库和本项目密钥，二者需成对保存。

回滚新服务：停用 `getvideo-api` 与 `getvideo-cert-renew.timer`，移除 `sites-enabled/getvideo` 链接，检查 nginx 后 reload；在腾讯云删除本次新增的 8443 规则。原 GetAudio 8800/443 不属于回滚范围。若回滚代码或数据库，先停止 GetVideo API，并保留当时新增的数据及对应密钥副本。
