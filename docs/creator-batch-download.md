# 博主主页批量下载（后端新增能力）

面向「传一个博主主页，批量下载指定范围视频，并带回点赞/评论等互动数据」的服务端实现。当前只做后端，Android APK 未接这些接口。

## 一、能力范围

- 输入：博主主页 URL、分享文案，或手机端常见标识：裸抖音号 `67194609689`、B 站 UID `2`、UP 主昵称；非 URL 输入必须带 `platform`（或文本里带平台标签），避免纯数字歧义。
- 范围：按发布时间倒序，`start` 是 1-based 起点，`count` 是数量；单次 1–50 条，`start` 最大 500。
- 数据：标题、简介、封面、时长、发布时间、播放/点赞/评论/分享/收藏/投币/弹幕数。
- 评论正文：B 站抓热门评论前 N 条；抖音当前只取评论计数，不抓正文（见平台差异）。
- 下载：任务创建后由后台单 worker 顺序调用现有 `pipeline.extract()`，落回原 `tracks` 表，继续复用账号隔离、去重、增量同步、播放列表和删除逻辑。
- 失败：单条视频失败不影响整批；平台授权失效会停止整批并把任务标记为 `failed`，重新授权后可 `retry`。

## 二、接口

所有接口都在 `/v2/creators` 下，使用 GetVideo 手机号登录后的 `X-GetVideo-Session` 头；任务和视频按 `owner_scope = 当前 user_id` 隔离。

### 0. 输入格式（`url` 字段）

`url` 字段名保留兼容，但它现在接受的不只是 URL；也可以用 `input` 或 `text` 作为同义字段：

| 用户提供的内容 | 请求示例 | 说明 |
| --- | --- | --- |
| 抖音号 | `{"input": "67194609689", "platform": "douyin"}` | 推荐；裸 ID，不要加「抖音号：」前缀 |
| 抖音 sec_uid | `{"input": "MS4wLjABAAAA..."}` | 直接当主页标识 |
| B 站 UID | `{"input": "2", "platform": "bilibili"}` | 裸 UID |
| B 站昵称 | `{"input": "碧诗", "platform": "bilibili"}` | 通过 B 站用户搜索找完全匹配 |
| 抖音昵称 | `{"input": "某博主", "platform": "douyin"}` | 通过抖音搜索页找；需要登录 Cookie + 浏览器桥接 |
| 主页/分享链接 | `{"input": "https://space.bilibili.com/2/video"}` | 原有方式完全不变 |
| 用户粘贴的带标签文本 | `{"input": "抖音号：67194609689"}` | 仅兼容；解析层会剥掉前缀，不是必填 |

- 单条视频链接会被拒绝，并提示改传博主主页。
- 昵称有重名时返回 404 并列出候选，请改用 UID/抖音号。
- `platform` 可选值：`bilibili`、`douyin`；链接与 `platform` 冲突时返回 400。

### 1. 预览（不下载）

```bash
curl -s http://127.0.0.1:8900/v2/creators/preview \
  -H "X-GetVideo-Session: $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "url": "https://space.bilibili.com/2/video",
    "start": 1,
    "count": 10,
    "include_stats": true,
    "include_comments": true,
    "comment_limit": 5
  }'
```

响应：

```json
{
  "profile": {
    "platform": "bilibili",
    "creator_id": "2",
    "name": "碧诗",
    "avatar_url": "https://...",
    "url": "https://space.bilibili.com/2/video",
    "total_videos": 44,
    "follower_count": 1433847,
    "signature": "..."
  },
  "range": {"start": 1, "count": 10},
  "items": [
    {
      "position": 1,
      "video_id": "BV1ujNV6qEXg",
      "title": "帮不帮？",
      "description": "...",
      "source_url": "https://www.bilibili.com/video/BV1ujNV6qEXg",
      "cover_url": "https://...",
      "duration_sec": 22.0,
      "published_at": "2026-07-12T...",
      "stats": {
        "view_count": 758568,
        "like_count": 89299,
        "comment_count": 9313,
        "share_count": 7219,
        "favorite_count": 12183,
        "coin_count": 4545,
        "danmaku_count": 2765
      },
      "comments": [
        {"author": "cmrmc", "content": "碧诗是什么ai", "like_count": 4307, "created_at": "..."}
      ],
      "comments_status": "ok",
      "download_status": null,
      "track_id": null,
      "reused": null,
      "error": null
    }
  ],
  "fetched_at": "2026-10-30T..."
}
```

`include_stats=false` 时不请求单条详情，速度快，但点赞/收藏等字段可能为空；`include_comments=true` 才会返回评论正文。

抖音号输入示例（裸 ID + platform，推荐）：

```bash
curl -s http://127.0.0.1:8900/v2/creators/preview \
  -H "X-GetVideo-Session: $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"input": "67194609689", "platform": "douyin", "start": 1, "count": 10}'
```

`{"input": "抖音号：67194609689"}` 也能解析，但只是为了兼容用户直接粘贴带标签的文本，不是接口的规范用法。

### 2. 创建批量任务

```bash
curl -s http://127.0.0.1:8900/v2/creators/jobs \
  -H "X-GetVideo-Session: $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "url": "https://space.bilibili.com/2/video",
    "start": 1,
    "count": 20,
    "include_comments": false,
    "force": false
  }'
```

返回 `202` 和任务摘要，`status=pending`。后台 worker 领取后依次下载。

### 3. 查询进度

```bash
GET /v2/creators/jobs?limit=20&offset=0
GET /v2/creators/jobs/{job_id}?include_items=true
GET /v2/creators/jobs/{job_id}/items?status=done&limit=50
```

任务状态：`pending | running | done | failed | cancelled`。
任务项状态：`pending | running | done | failed | cancelled`；`track_id` 指向已入库的视频，`reused=true` 表示命中去重。

### 4. 取消 / 重试

```bash
POST /v2/creators/jobs/{job_id}/cancel
POST /v2/creators/jobs/{job_id}/retry
```

取消会停止后续项；正在下载的一条会完成后再停。重试会把所有项重置为 `pending`，已下载的视频由 `pipeline.extract()` 去重，不会重复占用磁盘。

## 三、平台差异

### B 站（原生接口，已实测）

- 空间列表：`api.bilibili.com/x/space/wbi/arc/search`，带 WBI 签名和 dm 浏览器指纹参数。
- 主页信息：`x/web-interface/card`（昵称、头像、粉丝数、签名、稿件数）。
- 单条互动：`x/web-interface/view`（播放/点赞/评论/分享/收藏/投币/弹幕）。
- 热门评论：`x/v2/reply?type=1&oid=<aid>&sort=2`。
- 空间接口会偶发 412/-412；实现里会换访客 cookie 并以 1.5s 间隔重试 3 次。用户自己的登录凭证优先，不自动刷新用户凭证。
- 空间分页 `ps=20`；`ps=30` 在当前出口 IP 上更容易触发 412。

实测（2026-10-29，当前环境）：mid=2 预览返回 44 条稿件、点赞/评论/收藏等字段和热门评论；下载第 1 条 `BV1ujNV6qEXg` 成功，产出 720,706 字节 MP4，ffprobe 确认同时含 H.264 480×852 视频流和 AAC 音频流。

### 抖音（直连优先 + 可选浏览器桥接）

抖音 Web 的 `aweme/v1/web/aweme/post/` 现在被 `ArgusSecurityPlugin` 门禁。当前直连会得到：

```
403 Blocked by ArgusSecurityPlugin Uifid Not Found
```

因此实现分三层：

0. **抖音号解析**：先用公开的 `https://www.iesdouyin.com/web/api/v2/user/info/?unique_id=<抖音号>` 换 `sec_uid`。这条路不需要登录，当前实测 `67194609689` 能解析到「小叶稚」，主页作品数 259。
1. **直连**：带用户导入的抖音 Cookie 试一次 `aweme/post`。部分出口 IP/账号仍可能成功；成功时直接解析 `aweme_list`，`statistics` 里的点赞/评论/分享/收藏/播放数直接映射。
2. **浏览器桥接**：直连失败且 `GETVIDEO_DOUYIN_BROWSER=true` 时，用 Playwright 打开博主主页，监听页面自己发出的、已由抖音 SDK 签名的 `aweme/post` 响应，滚动采集到目标数量。需要：
   - 服务端安装 `playwright` 和 Chromium（或用 `GETVIDEO_DOUYIN_BROWSER_CHANNEL=chrome` 复用系统 Chrome）；
   - 没有登录 Cookie 时使用干净的浏览器上下文；部分出口可以直接看主页，部分会触发验证码。导入登录 Cookie 能显著提高成功率；
   - 抖音号/昵称**搜索**仍需要登录 Cookie；主页 URL / 已知 `sec_uid` 不需要；
   - 无头模式可能触发验证码；本地可把 `GETVIDEO_DOUYIN_BROWSER_HEADLESS=false`，人工完成一次验证。
3. **下载不二次请求详情**：作品列表里的 `aweme` 已含 `video.play_addr` 等播放地址，任务项会把原始 `aweme` JSON 存进 `creator_items.prefetched_json`；`process_job` 把它传给 `DouyinExtractor`，直接下载列表里的播放流。只有地址过期或缺失时才回退到 `aweme/detail`，避免每下载一条都撞一次 Argus。
4. 图集/图文作品没有视频流，采集时会跳过；`CreatorProfile.total_videos` 仍显示作者页作品总数，任务窗口的 `total` 是实际可下载视频数。
5. 抖音评论正文目前不批量抓取，`comments_status=count_only`，只保留 `stats.comment_count`。这是刻意不把「评论数」伪装成「评论内容」。

当前环境没有抖音登录 Cookie，但已用系统 Chrome 真实跑通：抖音号 `67194609689` →「小叶稚」→ 259 个作品 → 按发布时间最新一条 `7699794704686239011` 下载成功。临时产物为 932,664 字节 MP4，17.1 秒，H.264 1080×1440 + AAC；验证后已删除临时文件和临时库，没有写入生产库。

## 四、部署

B 站功能不需要新依赖。抖音浏览器桥接是可选依赖：

```bash
.venv/bin/pip install -r requirements-douyin.txt
.venv/bin/python -m playwright install chromium
```

新增环境变量（都可选）：

```dotenv
GETVIDEO_CREATOR_WORKER_ENABLED=true
GETVIDEO_CREATOR_WORKER_POLL_SECONDS=2
GETVIDEO_CREATOR_MAX_ITEMS=50
GETVIDEO_CREATOR_MAX_START=500
GETVIDEO_CREATOR_REQUEST_INTERVAL=0.15
GETVIDEO_CREATOR_DOWNLOAD_INTERVAL=0.5
GETVIDEO_DOUYIN_BROWSER=true
GETVIDEO_DOUYIN_BROWSER_HEADLESS=true
GETVIDEO_DOUYIN_BROWSER_TIMEOUT=45
GETVIDEO_DOUYIN_BROWSER_MAX_SCROLLS=20
# 留空用 playwright 自带 Chromium；也可以填 chrome / msedge 用系统浏览器。
GETVIDEO_DOUYIN_BROWSER_CHANNEL=
```

单进程部署时 worker 是单线程顺序执行；如果以后用多个 uvicorn worker，应只让一个进程启动 creator worker，或把任务队列换成独立 worker 进程。

## 五、数据表与迁移

新增两张表，启动时由 `Base.metadata.create_all()` 自动建表，不改 `tracks` 字段：

- `creator_jobs`：任务、范围、进度、状态、错误。
- `creator_items`：每条视频的元数据、互动数据、评论 JSON、下载状态、`track_id`。

任务项与 `tracks.id` 通过 `track_id` 关联；视频文件仍按现有 `owner_scope` 隔离目录规则存放，路径不暴露给其他账号。

## 六、参考实现

调研过、并按其接口约束实现的公开项目/文档：

- [SocialSisterYi/bilibili-API-collect](https://github.com/SocialSisterYi/bilibili-API-collect)：B 站空间、视频详情、评论接口字段。
- [yt-dlp](https://github.com/yt-dlp/yt-dlp)：`BilibiliSpaceVideoIE` 的 WBI 签名、dm 指纹参数和 412 处理。
- [jiji262/douyin-downloader](https://github.com/jiji262/douyin-downloader)：2026 年抖音 `aweme/post` 的 Argus 门禁、`uifid`/浏览器桥接思路；本项目没有复制其代码，只按相同边界实现。
- [JoeanAmier/TikTokDownloader](https://github.com/JoeanAmier/TikTokDownloader)：抖音主页作品、点赞、评论数据的抓取范围参考。
- [Johnserf-Seed/f2](https://github.com/Johnserf-Seed/f2)：抖音 `a_bogus` 算法参考；当前实现没有引入该依赖，避免把易变的签名算法绑进服务端。

## 七、已知限制

- 单任务最多 50 条；范围按「最新优先」序号，暂不支持按日期区间筛选。
- B 站空间接口仍可能 412；需要用户登录凭证或稍后重试。
- 抖音浏览器桥接依赖 Chromium；登录 Cookie 不是主页浏览的硬前提，但能降低验证码概率，抖音号/昵称搜索仍必须登录。
- 抖音主页无登录抓取是否成功取决于出口 IP 和当前风控；无头服务器上验证码无法自动完成。
- 抖音评论正文未抓取，只取计数；点赞/收藏等来自 `statistics`，若平台返回 0 或缺失会保留 `null`。
- 当前 APK 未接这些接口；`tests/test_api_contract.py` 已将 `/v2/creators/*` 加入「后端先行」跳过列表。
