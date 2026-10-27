# 0.3.0：批量提取、自定义背景、只看背景、B站 412 加固

本次四件事：把 B站 412 真正修掉、提取页支持一次粘贴多条分享文本、App 支持用自定义图片当页面背景，以及库页新增「只看背景」。

## 一、B站 HTTP 412

现象：手机直提取公开 B站 链接报 `HTTP 412`，短链展开那一步是通的，卡在带 WBI 签名的接口上。

原因不是签名算法，而是请求上下文里没有有效的访客凭证：`api.bilibili.com` 对没有 `buvid3` 的请求直接返回 412。

处理（`DirectExtractor`）：

1. 新增 `refreshBiliCredentials()`，请求 `https://api.bilibili.com/x/frontend/finger/spi` 取 `b_3`/`b_4`，分别写入 `api.bilibili.com`、`www.bilibili.com` 两个域的 `buvid3`/`buvid4`。`spi` 接口本身几乎不被风控，是最稳的起点。
2. 新增 `biliGet()`：遇到 412 时刷新一次凭证、等 500ms 后原样重试一次，其它错误码不重试。
3. 普通 `get()` 增加 `Origin`、`Sec-Fetch-Site/Mode/Dest`、`Accept-Language` 头，让请求更像从站内页面发出的。
4. `MemoryCookies.put` 必须先 `trimStart('.')`：OkHttp 的 `Cookie.Builder().domain()` 不接受 `.bilibili.com` 这种点前缀，否则抛 `IllegalArgumentException: unexpected domain`（这是第一次修复没生效的原因）。
5. 抖音 Cookie 域统一写成 `www.douyin.com`。

真机日志：`B 站首页：HTTP 200，访客 Cookie 有` → `访客凭证 已取得` → `画面 3 个地址，声音 3 个地址` → `视频封装完成：mp4`。

## 二、批量提取

多个分享链接经常被用户揉成一段文本一起粘进来，所以识别和去重是关键。

### 识别（`data/localextract/LinkScanner.kt`）

采用**最长匹配**：从 `http(s)://` 或裸域名（`b23.tv/`、`v.douyin.com/` 等）起，向后连续吃 URL 合法 ASCII 字符，遇到空白、中文、全角标点、引号、emoji 立即停止；尾部再修剪一次标点，但保留短链结尾的斜杠。这样既不会把后面的中文吃进链接，也不会把紧挨着的下一条链接粘成一条。

补一条兜底：文本里出现裸的 `BV[0-9A-Za-z]{10}` 也认。

### 去重

按规范化地址或 `(platform, key)` 去重。`key` 取法：

- B站：路径里的 `BVxxxxxxxxxx` 或 `av123`；
- 抖音：`video|note/(\d+)` 或查询串里的 `modal_id`；
- 都没有（短链）：**用路径末段**，形如 `短链:jku1JAdzpCY`。

最后一条容易踩坑：早期版本所有短链都返回固定的 `"短链"`，导致同平台的多条短链被当成同一条去重掉——三条抖音只识别出一条。改成路径末段后，`识别到 4 个链接（B站 2 · 抖音 2）` 正确。

### 交互

- 输入框仍然是原来的一个，不额外加批量输入框：粘贴即识别，识别到 1 条按单条处理，多条自动切成批量，主按钮文案变成「批量提取 N 条」。
- 识别到多条时先展示清单（序号、平台、地址），让用户确认后再点；从系统分享进来时，多条也是先列清单不自动执行，避免一次触发一串下载。
- 执行是**串行**的，逐条走原有直连解析流程，单条失败不影响后面的条目。
- 结束时在页面上留一张结果卡：「批量完成：N 条全部成功」或「成功 A 条，失败 B 条」，失败的链接和原因列在下面，不会只闪一下提示条就消失。
- 批量模式隐藏「从服务器提取」——服务端通道只支持单条，留着会点了没反应。

## 三、自定义背景

- `data/repo/BackgroundStore.kt` 只存三样：图片地址、遮罩强度、内置图名，存在 `appearance` 这个 SharedPreferences 里。相册图用 `OpenDocument` + `takePersistableUriPermission` 读取，重启后仍然有效。
- `ui/components/BackgroundLayer.kt` 在最底层画一次图（`ContentScale.Crop`），再叠一层同色调的竖向渐变遮罩。
- `LocalBackgroundEnabled` 这个 CompositionLocal 让 `uiPageBackground()` 在启用背景时返回 `Color.Transparent`，页面把底色让出来；各页内层 `Scaffold` 的 `containerColor` 也必须是 `Transparent`，否则默认的 `#FFFBFE` 会把整张图盖住（这是背景只在下半屏可见的原因）。
- **卡片、按钮、分组容器一律保持不透明**，所以可读性和点击都不受影响。
- 遮罩默认 22%，顶部略重一点保证状态栏和页面大标题可读，往下逐渐放轻；设置页有 6%–90% 的滑杆。
- 图片**不做模糊**：第一版加了 20dp 模糊，结果整屏糊成一片色块，看不出背景是谁，已去掉。
- 内置图：`assets/furina.jpg`（芙宁娜立绘，1024×1494），选中即用，不联网。
- 入口在「我的 → 外观 → 背景图片」，页内有预览卡、相册选择、内置图、恢复默认、压暗滑杆。

### 卡片不透明度（可调）

列表里的白色卡片一开始是纯白的，背景只在卡片之间的缝隙里露出来，等于白忙一场。改成半透明后又没法兼顾所有人对「透」和「好读」的偏好，于是做成滑杆：

- `AppearanceBackground.cardAlpha` 存在 `appearance` 里，默认 0.58；外观页「卡片不透明度」滑杆范围 12%–100%，100% 就是完全不透明的纯白卡片。
- 传递方式是 `LocalCardAlpha` 这个 CompositionLocal，`MainActivity` 从 `BackgroundStore.state` 取值提供给整棵树，`uiCardColor()` / `uiHairline()` / `uiAccentContainer()` 都跟着它算透明度，所以调一次全 App 的卡片、分组、分隔线一起变。
- 深色主题下卡片底色调亮一点（`cardAlpha + 0.16`），否则白字压在透光的深底上会花。
- 预览卡里放了一张真的示例卡片（同样走 `uiCardColor()`），拖动滑杆立刻能看到「图透出来多少、字还清不清楚」。
- 实测：58% 时芙宁娜面部清晰穿过卡片、正文仍可读；35% 时卡片接近纯玻璃，副标题在亮部会开始吃力。滑杆的意义就是让用户自己找平衡点。
- 外观页内容变长后需要滚动，给根 `Column` 补了 `verticalScroll`，否则新加的滑杆会被底部导航压住。


## 四、弹层体系

上一次的 `MusicSheet` / `SheetRow` / `CategoryCard` 弹层继续复用：队列、三点菜单、播放列表、播放方式、类别页、剪辑页全部走同一套卡片 + 胶囊风格，主色统一取 `MaterialTheme.colorScheme.primary`，不再出现蓝色和紫色两套色系打架。

## 五、只看背景

- 入口在视频库顶部工具行右侧（「云端同步」那一行的末尾），按钮文案「只看背景」。
- 点击后整个 `Scaffold`（列表 + 底部导航 + 队列条）都不再组合，屏幕上只剩 `BackgroundLayer` 画的那张图，加一个悬浮在底部导航之上的「退出背景」胶囊。
- 退出有两条路：点胶囊，或按系统返回键（`BackHandler(enabled = bgOnly)`）。
- 胶囊用不透明 `Surface` 垫底并加了 `navigationBarsPadding()`——第一版只写了 `bottom = 44.dp`，结果正好落在系统手势区内，点下去被系统截走、直接回了桌面。
- 没设背景图时进入这个模式，会给一行提示「还没有设置背景图 · 可在「我的 → 外观」里选一张」，不会给一片空白。
