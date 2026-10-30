# GetVideo HTTP API（curl）

服务端默认地址：`http://127.0.0.1:8900`。手机号账号体系用 `X-GetVideo-Session`，不要用 nginx 的 Basic Auth 头。

先拿 token：

```bash
BASE=http://127.0.0.1:8900
curl -s "$BASE/account/register" \
  -H 'Content-Type: application/json' \
  -d '{"phone":"13900000000","password":"test-password-123"}'

curl -s "$BASE/account/login" \
  -H 'Content-Type: application/json' \
  -d '{"phone":"13900000000","password":"test-password-123"}'
```

返回里的 `session_token` 就是后续的 `TOKEN`：

```bash
TOKEN='...'
AUTH="X-GetVideo-Session: $TOKEN"
```

## 1. 提取与解析

### 解析视频链接（不下载）

```bash
curl -s "$BASE/v2/resolve" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"text":"https://www.bilibili.com/video/BV1ujNV6qEXg"}'
```

返回 `platform`、`video_id`、`track_key`、`url`、`page`。

### 提取视频

```bash
curl -s "$BASE/v2/extract" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"text":"https://www.bilibili.com/video/BV1ujNV6qEXg","force":false}'
```

### 提取任务记录

```bash
curl -s "$BASE/v2/extraction-jobs?limit=50" -H "$AUTH"
```

返回当前账号的提取任务状态、错误、`track_id`，不返回其他账号的任务。

## 2. 视频库

### 列表 / 搜索

```bash
curl -s "$BASE/v2/tracks?limit=50&offset=0" -H "$AUTH"
curl -s "$BASE/v2/tracks?q=关键词&platform=bilibili" -H "$AUTH"
```

### 单条详情

```bash
curl -s "$BASE/v2/tracks/123" -H "$AUTH"
```

### 改名 / 改 UP 主 / 改分类

```bash
curl -s -X PATCH "$BASE/v2/tracks/123" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"title":"新标题","uploader":"新 UP 主","categories":["治愈"],"has_vocal":true}'
```

`categories` 可选值：`忧伤 治愈 热血 卡点 古风 搞笑 钢琴 电子 摇滚 爵士 古典 环境 其他`。改分类后 `classify_stage` 会记为 `manual`。

### 删除 / 恢复

```bash
curl -s -X DELETE "$BASE/v2/tracks/123" -H "$AUTH"
curl -s -X POST "$BASE/v2/tracks/123/restore" -H "$AUTH"
```

删除是逻辑删除：服务器文件保留，`/v2/sync` 会下发 tombstone，手机端据此移除。

### 增量同步

```bash
curl -s "$BASE/v2/sync?cursor=0&limit=200" -H "$AUTH"
```

### 下载视频文件

```bash
curl -L "$BASE/v2/video/123" -H "$AUTH" -o video.mp4
```

## 3. 博主批量

```bash
# 预览：不下载
curl -s "$BASE/v2/creators/preview" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"input":"67194609689","platform":"douyin","start":1,"count":10}'

# 创建下载任务
curl -s "$BASE/v2/creators/jobs" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"input":"67194609689","platform":"douyin","start":1,"count":10}'

# 进度 / 取消 / 重试
curl -s "$BASE/v2/creators/jobs/1" -H "$AUTH"
curl -s -X POST "$BASE/v2/creators/jobs/1/cancel" -H "$AUTH"
curl -s -X POST "$BASE/v2/creators/jobs/1/retry" -H "$AUTH"
```

`input` 也可以是 `https://space.bilibili.com/2/video`、`B站UID：2`、`碧诗` 或 `MS4wLjAB...`。`platform` 用于消除纯数字歧义。

## 4. 平台登录与凭证

```bash
# 当前支持的平台
curl -s "$BASE/v2/platforms" -H "$AUTH"

# B 站扫码
curl -s -X POST "$BASE/v2/platforms/qr" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"platform":"bilibili"}'
curl -s "$BASE/v2/platforms/qr/<session_id>" -H "$AUTH"

# 导入浏览器 cookie（抖音走这条）
curl -s -X POST "$BASE/v2/platforms/cookies" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"platform":"douyin","cookies":"sessionid=...; ttwid=..."}'

# 查看/撤销凭证（只回状态，不回 cookie）
curl -s "$BASE/v2/platforms/credentials" -H "$AUTH"
curl -s -X DELETE "$BASE/v2/platforms/credentials/douyin" -H "$AUTH"
```

平台账号绑定/OAuth 在 `/account/platforms`、`/account/platform-auth/*`、`/account/platform-bindings`；这些是账号身份绑定，不是视频提取。

## 5. Agent

```bash
# request_id 用 UUID，重复提交同一 request_id 会走幂等
curl -s -X POST "$BASE/agent/v2/chat" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"request_id":"550e8400-e29b-41d4-a716-446655440000","message":"帮我找治愈的钢琴曲","local_tracks":[]}'

curl -s "$BASE/agent/v2/conversations" -H "$AUTH"
curl -s "$BASE/agent/v2/conversations/<cid>" -H "$AUTH"
```

记忆偏好：

```bash
curl -s -X POST "$BASE/v2/memories" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"user_id":"<自己的 user_id>","content":"喜欢钢琴"}'
curl -s "$BASE/v2/memories/<自己的 user_id>" -H "$AUTH"
```

## 6. AI 视频描述与重建

```bash
curl -s "$BASE/v2/recreations/capabilities" -H "$AUTH"
curl -s "$BASE/v2/recreations" -H "$AUTH"

# 从已有视频创建项目
curl -s -X POST "$BASE/v2/recreations/from-track/123" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"request_key":"req-12345678","analyze_audio":true}'

curl -s "$BASE/v2/recreations/<pid>" -H "$AUTH"
curl -s -X POST "$BASE/v2/recreations/<pid>/analyze" -H "$AUTH" -H 'Content-Type: application/json' -d '{}'
curl -s -X POST "$BASE/v2/recreations/<pid>/generate" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"request_key":"gen-12345678","revision":1,"mode":"text","resolution":"480p","preview":true}'
```

## 7. 手机提取失败诊断

```bash
curl -s "$BASE/v2/diagnostics/extractions?limit=50" -H "$AUTH"
curl -s "$BASE/v2/diagnostics/extractions/<report_id>" -H "$AUTH"
```

## 8. 维护接口

均为当前账号范围，不会扫到其他账号。

```bash
# 重新分类（limit 1..1000）
curl -s -X POST "$BASE/v2/maintenance/reclassify?limit=100&force=true" -H "$AUTH"

# 按已有 sha256 修复历史音频大小
curl -s -X POST "$BASE/v2/maintenance/repair-audio-sizes" -H "$AUTH"

# 补声音指纹
curl -s -X POST "$BASE/v2/maintenance/fingerprints/backfill?limit=500" -H "$AUTH"

# 找相似/重复候选（只返回候选，不删除）
curl -s "$BASE/v2/maintenance/duplicates?limit=500&threshold=0.95" -H "$AUTH"
```

## 9. 定时抓取

```bash
# 查看配置和状态（不返回关键词/链接）
curl -s "$BASE/v2/scheduler" -H "$AUTH"

# 只触发当前账号自己的组；全局组仍由独立 scheduler worker 执行
curl -s -X POST "$BASE/v2/scheduler/run-once?force=true" -H "$AUTH"
```

## 10. 健康检查

```bash
curl -s "$BASE/health"
```

## 没有服务端接口的部分

下面这些是 **Android 本地数据/本机能力**，服务端没有对应表，因此没有硬造 HTTP 接口：

- 播放列表、播放队列、定时播放；
- 本地剪辑、导出；
- 手机直提取（不经过服务端）；
- 本地背景/壁纸、排序拖动。

如果要把播放列表也做成多设备同步，需要先新增服务端表和同步协议，属于单独功能，不是补一个路由就能完成。

## 旧版接口

`/extract`、`/tracks`、`/sync`、`/video/{id}`、`/login/*`、`/credentials/{user_id}` 仍然保留，主要给旧客户端和 nginx Basic Auth 管理通道使用；手机端新功能统一走 `/v2/*`。
