# 视频生成模型调用能力核验（2026-10-18 北京时间）

执行：Codex。项目代码基线 GetVideo@9996b13，本文件为未提交调研记录。核验当前两把共享 API key 的模型目录及调用状态，没有修改 key、充值、模型开通或后付费设置，没有修改运行代码或重启服务。

## 明确结论

**不是所有视频生成模型都不能用。火山 doubao-seedance-1-0-pro-fast-251015 已完成真实的文生视频调用，状态 succeeded，结果 MP4 已下载并通过 ffprobe 检查。** 前一轮只测试较新的 Seedance 和 Kling Omni，不足以代表全部视频生成型号。

最小有效测试：一个红色正方形在深蓝背景上向右移动，5 秒、480p、16:9，无个人视频、人物或真实隐私素材。实际文件 H.264、864×480、5.083333 秒、1,372,683 字节。模型返回 completion_tokens / total_tokens 均为 49,005；接口没有返回本次金额、剩余免费额度或使用了哪种计费方式，因此不能称为免费。没有进行第二次有效生成。

结果文件：`artifacts/model-access-audit/seedance-1.0-pro-fast-5s-480p.mp4`。
SHA256：`69d94c4caa4024475eb747aa76dec8f8af2f6f93b76fc18181cdac9980bb7d55`。

## 方法与证据边界

重新使用两把 key 分别请求 /models，均返回 HTTP 200。筛出火山 output_modalities 含 video 的 13 个型号；腾讯按视频生成名称和官方模型用途筛出 25 个型号，排除视频理解模型 hunyuan-turbos-vision-video-20250728。目录包含停用或退役型号，不能把列表数量当作可用数量。

首先逐型号发送缺少必填输入、空内容/提示词、零时长等不能生成视频的请求：
- 明确返回 ModelNotOpen 或额度/后付费拒绝的，记录当前 key 下的调用阻断原因；这是账户/网关拒绝，不把参数错误当作不可用。
- InvalidEndpointOrModel.NotFound 与目录状态结合记录，不独立断言模型是永久不存在还是该账号无权限。
- 只有参数错误的，属于待验证，不能宣布能生成；也不能宣布不能生成。

火山 1.0 Pro Fast 返回内容参数校验错误，而非开通拒绝，因此进一步只对它发出一次合法的最低档短样片请求，得到任务 ID，GET 轮询至 succeeded，下载结果并检查真实视频流。

38 个型号的无效输入核验没有返回任何任务 ID。唯一真实生成是上面的 1.0 Pro Fast 样片。详细逐型号响应已经脱敏保存在忽略 Git 的 artifacts/model-access-audit/，不含密钥和素材签名 URL。

## 火山方舟：13 个

| 模型 ID | 目录状态 | 当前 key 核验结果 |
| --- | --- | --- |
| doubao-seaweed-241128 | Shutdown | HTTP 404 / InvalidEndpointOrModel.NotFound |
| wan2-1-14b-i2v-250225 | Shutdown | 同上 |
| wan2-1-14b-t2v-250225 | Shutdown | 同上 |
| wan2-1-14b-flf2v-250417 | Shutdown | 同上 |
| doubao-seedance-1-0-lite-i2v-250428 | Retiring | HTTP 404 / InvalidEndpointOrModel.NotFound |
| doubao-seedance-1-0-lite-t2v-250428 | Retiring | 同上 |
| doubao-seedance-1-0-pro-250528 | Retiring | 同上 |
| **doubao-seedance-1-0-pro-fast-251015** | 在列，未标 Shutdown/Retiring | **合法文生请求 HTTP 200；任务 succeeded；已下载真实视频** |
| doubao-seedance-1-5-pro-251215 | Shutdown | HTTP 404 / InvalidEndpointOrModel.NotFound |
| doubao-seedance-2-0-260128 | 在列 | HTTP 404 / ModelNotOpen |
| doubao-seedance-2-0-fast-260128 | 在列 | HTTP 404 / ModelNotOpen |
| doubao-seedance-2-0-mini-260615 | 在列 | HTTP 404 / ModelNotOpen |
| doubao-seedance-2-5-260628 | 在列 | HTTP 404 / ModelNotOpen |

合计：1 个已实际出片，4 个明确未开通，8 个返回模型/端点不存在或不可访问（其中目录分别标记 5 个 Shutdown、3 个 Retiring）。

## 腾讯 TokenHub：25 个

以下 24 个型号逐个请求其生成接口，均返回 **HTTP 402 / 401007**。中文错误明确为“服务无可用免费体验额度，且未开启后付费，无法正常访问”。没有逐个发起会创建视频的合法生成请求，也没有创建任务 ID。

| 家族 | 精确 model ID | 当前结果 |
| --- | --- | --- |
| Kling | kling-video-v3 | HTTP 402 / 401007 |
| Kling | kling-video-v3-turbo | 同上 |
| Kling | kling-video-v3-omni | 同上 |
| Kling | kling-video-o1 | 同上 |
| Kling | kling-video-v2.6 | 同上 |
| Kling | kling-video-v2.5-turbo | 同上 |
| Kling 动作控制 | kling-video-motion-control-v2.6 | 同上 |
| Kling 动作控制 | kling-video-motion-control-v3.0 | 同上 |
| MiniMax | minimax-video-h3-max | 同上 |
| MiniMax | minimax-video-h3 | 同上 |
| MiniMax | minimax-video-v2.3 | 同上 |
| MiniMax | minimax-video-v2.3-fast | 同上 |
| PixVerse | pixverse-video-c1 | 同上 |
| PixVerse | pixverse-video-v6.0 | 同上 |
| PixVerse | pixverse-video-v5.6 | 同上 |
| Vidu | vidu-video-q3-pro | 同上 |
| Vidu | vidu-video-q2-turbo | 同上 |
| Vidu | vidu-video-q3-turbo | 同上 |
| Vidu | vidu-video-q3 | 同上 |
| Vidu | vidu-video-q2 | 同上 |
| Vidu | vidu-video-q2-pro | 同上 |
| Vidu | vidu-video-q4-preview | 同上 |
| 混元 | hy-video-v1.5 | 同上 |
| WAND | wand-vega-video-1.0-lite | 同上 |

**剩下 1 个：yt-video-humanactor（优图人像驱动）**。

通过官方文档列出的 POST /v1/api/video/submit 请求，HTTP 200，但响应 error.code=InvalidParameter.InvalidParameter，message=AudioUrl is invalid，没有任务 ID。HTTP 200 在这里不是生成成功。

该接口用途是“参考人物/宠物图片 + 音频 → 驱动视频”，不是通用的纯文字生成；官方要求 AudioUrl、图片 URL 或 Base64。当前只做到参数层核验，尚未用完整有效素材创建并完成任务。因此状态为 **未确认**：不能把它归为 HTTP 402 的 24 个型号，也不能称为已经可用。已经找到并实际验证 1.0 Pro Fast 后，为控制额外用量，本轮没有再替它提交有效生成。

## 对 GetVideo 功能的实际影响

可以先采用已经实测成功的组合：
- 视频画面 → Seed 2.1 Pro → 详细分镜文字；
- 声音需要单独分析时使用 Seed 2.0 Mini；
- 确认后的文字 → Seedance 1.0 Pro Fast → 视频。

本轮只验证 1.0 Pro Fast 的纯文字生视频。目录还声明其支持图片输入，但没有单独做图生验收；不把 Seedance 2.5 的视频/音频全模态参考能力套用到它身上。能实现两步流程，不等于已经验证“接近原片”的质量；仍需按实际支持能力调整提示词、镜头分段和声音后期策略。

## 请求路径来源（官方）

- 火山视频生成任务：https://docs.volcengine.com/docs/ark/create-video-generation-task-api?lang=zh
- 腾讯 Kling：https://cloud.tencent.com/document/product/1823/135742
- 腾讯 MiniMax：https://cloud.tencent.com/document/product/1823/135737
- 腾讯 PixVerse：https://cloud.tencent.com/document/product/1823/135325
- 腾讯 Vidu：https://cloud.tencent.com/document/product/1823/135756
- 腾讯混元：https://cloud.tencent.com/document/product/1823/137202
- 腾讯 WAND：https://cloud.tencent.com/document/product/1823/138195
- 腾讯优图：https://cloud.tencent.com/document/product/1823/135716
- 人像驱动输入要求：https://cloud.tencent.com/document/api/1616/125458

当前结论只对应核验时刻和这两把 key/所属项目；服务开通、额度和权限变化后应重新检查。此记录没有写入飞书。
