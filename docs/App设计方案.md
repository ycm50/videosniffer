# hanime1 App 设计方案

> 版本：v1.1 · 2026-08-03（v1.1 已按实现回填）
> 依据：《资源嗅探技术调研.md》《多线程下载技术调研.md》
> 状态：**M1–M5 已实现**。文档中标注「实现偏差」处为落地时与初版方案的差异，均以代码为准。

---

## 目录

- [一、产品形态与需求](#一产品形态与需求)
- [二、技术选型](#二技术选型)
- [三、总体架构](#三总体架构)
- [四、页面与交互设计](#四页面与交互设计)
- [五、核心功能设计](#五核心功能设计)
- [六、数据模型](#六数据模型)
- [七、工程包结构](#七工程包结构)
- [八、依赖与权限](#八依赖与权限)
- [九、实现里程碑](#九实现里程碑)
- [十、风险与对策](#十风险与对策)

---

## 一、产品形态与需求

一个 **内置浏览器 + 资源嗅探 + 多线程下载管理** 的 Android App：

1. 主界面是 **系统 WebView 浏览器**，可正常浏览目标站点及从页内点开的链接
2. 播放视频时自动嗅探视频资源，弹出**下载按钮**（含清晰度选择）
3. 底部导航第二个 Tab 是**独立下载管理界面**
4. 下载采用**多线程分片/分段**，支持**断点续传**，**线程数可配置**
5. 下载完成后的视频放在 **公共 Download/ 目录**（`/Download/hanime1/`）
6. 设置里可切 **「电脑版网页」**：用桌面 UA 与桌面视口加载页面，便于访问只在桌面端给出完整内容的站点
   （保存后浏览器页自动重新加载生效）

---

## 二、技术选型

| 项 | 选择 | 理由 |
|---|---|---|
| 浏览器内核 | **系统 WebView**（`android.webkit.WebView`） | 需求是浏览时嗅探；不引入 XWalkView/Crosswalk；minSdk 29 下系统 WebView 已支持 `shouldInterceptRequest`（API 21+）与 JS 注入 |
| UI | XML + Material（现有依赖，不引入 Compose） | 项目未配 Compose；WebView 为主，XML 足够，轻量 |
| 网络 | OkHttp | Range 请求、拦截器、响应头读取成熟 |
| 持久化 | **原生 `SQLiteOpenHelper`**（实现偏差，原方案为 Room） | 仅两张表、无关系映射需求；避开 KSP 注解处理，保证 AGP 9 内置 Kotlin 下编译稳定 |
| 并发 | kotlinx-coroutines + Semaphore | 下载引擎用协程 + 信号量控制并发，UI 用协程 |
| 播放 | 系统 WebView 内嵌播放 | 需求是「浏览时嗅探」，不另做播放器 |
| m3u8 合并 | **MPEG-TS 字节级 concat + fMP4 `#EXT-X-MAP` 支持**（首选）/ ffmpeg 可选 | 免原生依赖；AES-128 走 javax.crypto 解密，不打包 ffmpeg |
| 设置 | **SharedPreferences**（实现偏差，原方案为 DataStore） | 设置项少且同步读写，引入 DataStore 收益不成正比 |

---

## 三、总体架构

```
┌────────────────────────────────────────────────┐
│                   UI 层 (Activity + 3 个 Screen)      │
│  Browser页     Download列表页    Settings页        │
└──────────┬──────────────┬─────────────────────┘
           │ Flow 观察     │ 操作命令
┌──────────▼──────────────▼─────────────────────┐
│               ViewModel 层                      │
│  BrowserVM    DownloadListVM    SettingsVM      │
└──────────┬─────────────────────────────────────┘
           │
┌──────────▼─────────────────────────────────────┐
│            DownloadManager (单例/Service)        │
│  ┌─────────┐  ┌──────────────┐  ┌────────────┐  │
│  │ 嗅探引擎  │→ │ 任务调度/限流  │→ │ 下载引擎     │  │
│  └─────────┘  └──────────────┘  └────┬───────┘  │
└──────────────────────────────────────┬─────────┘
                                       │
┌──────────▼──────────────────────────▼─────────┐
│  数据层: Room (Task/Segment) + DataStore (设置) │
└────────────────────────────────────────────────┘
```

单向数据流：UI → ViewModel → DownloadManager(状态流) → SQLite 持久化 → 恢复。
（图中数据层的 `Room + DataStore` 是原方案，落地改为原生 SQLite + SharedPreferences，理由见「技术选型」。）

---

## 四、页面与交互设计

### 4.1 浏览器页（默认页）

- 顶部：地址栏 + 前进/后退/刷新
- 中部：系统 WebView（`hanime1.me` 主页进入；页内链接在**同一 WebView 打开**，拦截外链）
- WebView 配置：启用 JS、DOM Storage、混合内容按需；`WebViewClient` 控制页内导航与请求回调
- UA：默认**移动版 Chrome UA**；开启「电脑版网页」（见 4.3）后换成 Windows Chrome 桌面 UA +
  桌面视口，切回本 Tab 时自动重新加载生效
- 浮动下载按钮：嗅探到媒体资源时显示（列表非空即常驻），点击弹出清晰度选择 Dialog，
  每行显示 `作品名 · 清晰度 · 大小 · 格式`
- 嗅探**不做 JS 注入**：靠 `shouldInterceptRequest` 请求级拦截（Route A）+ 站点页面解析（Route D）
  双通道；<1 MB 的候选按「疑似广告/预告」过滤，全被过滤时给一次 Toast 提示

### 4.2 下载列表页

- RecyclerView 列表：标题 / 清晰度 / 文件大小 / 进度条 / 实时速度 / 并发连接数 / 状态徽标
- 每项操作按钮：**暂停 / 继续 / 取消 / 删除 / 重试 / 打开文件**；失败任务额外显示失败原因
- 顶部：标题 + 手动刷新（立即同步）+ **清空已结束**
- **列表双通道刷新**：订阅 `DownloadManager.tasks`（StateFlow，状态迁移即时）+ 每 **500ms** 轮询 `tasks.value`（兜底）
- `DownloadManager` 发布的任务是**不可变快照**（每次 `copy()`），订阅者拿到的实例永不被就地改写
- 已完成任务通过 MediaStore 返回的 content URI 打开（无需额外权限）

> 尚未实现（后续可加）：顶部聚合栏（总进度/活跃数）、状态过滤 Tab。
>
> **根因记录 —— 为什么 `tasks` 曾经永远不发通知：**
> `DownloadTask` 是 `data class`，而 `MutableStateFlow` 用**相等性**判重
> （`oldState == newState` 时直接返回、不广播）。旧实现 `updateTask` 是
> `list.map { if (it.id == task.id) task else it }` —— 放回的是**已发布过的同一个实例**，
> 于是新旧列表逐元素比较的是同一个对象，`equals` 恒为 `true`，
> StateFlow 认为「值没变」而**一次都不广播**。
>
> 症状全都由此而来：进度条/速度静止（只能靠手动「刷新」直读 `tasks.value`）、
> 点「暂停」后按钮不变成「继续」、通知栏百分比不动。
> 注意这**不是**订阅写法的问题 —— 订阅一直是正确的，是数据源从不发信号。
>
> 修复：列表只放不可变快照 —— 引擎在自己的私有副本（`launchTask` 里的 `work`）上就地累加，
> 每处发布都插入 `copy()` / 用 `mutateTask` 替换副本，绝不就地修改已发布实例。

### 4.3 设置页

- **全局线程数滑块（1~32，默认 4）**，存 SharedPreferences，新任务生效
- **同时下载任务数（1~5，默认 3）**，超出上限的任务进入 `QUEUED` 排队
- 初始网站（保存后浏览器**立即切换**，无需重启）
- **电脑版网页开关（默认关）**：开启后 WebView 换成 Windows Chrome 桌面 UA
  （`useWideViewPort` + `loadWithOverviewMode` 让没有 viewport meta 的桌面版页面按 980px 排版后整体缩放），
  保存后浏览器页**自动 reload** 生效。只作用于 WebView 的页面加载，
  **不影响**嗅探/下载请求的 UA —— 那些请求靠固定 UA 过防盗链，与页面版本无关
- 下载目录说明（固定导出到 `Download/hanime1/`）
- 清空已结束记录（列表页顶部按钮）

---

## 五、核心功能设计

### 5.1 资源嗅探（双通道）

针对 hanime1.me 特性（调研结论：**不需要通用嗅探，解析下载页最稳定**）。

**主通道 —— 下载页解析（Route D）**
1. `WebViewClient.onPageFinished` / URL 变化时判断是否命中 `hanime1.me/watch?v=<vid>`
2. 命中则后台请求 `hanime1.me/download?v=<vid>`，解析 `table.download-table` 下 `<a download>` 标签
3. 正则 `[^/]+-\d+p` 提取清晰度 → 得到 `{1080p: url, 720p: url, ...}` mp4 直链
4. HEAD 请求补全 `Content-Length` 与 `Content-Type`（octet-stream 按 mp4 处理）
5. 取作品名：`<a download>` 只是「同名文件」（形如 `40-1080p.mp4`）**不含作品名**，因此优先用
   WebView 上报的网页标题，为空时回退抓取 watch 页的 `<h1>`（再退 `<title>`，剥掉站点名尾巴）
6. 体积过滤：已知大小 < 1 MB 的候选丢弃（站点在下载页插入的广告/预告短 mp4，
   在清晰度列表里与正片混排极易误选）；大小未知时保留，避免误杀正片
7. 候选列表全部被过滤时弹一次提示，避免用户误以为嗅探失效

**辅通道 —— 请求级拦截（Route A，兜底）**
- `shouldInterceptRequest` / `onLoadStarted` 收集所有请求 URL
- 后台 HEAD 验证扩展名 + Content-Type + Content-Length，识别 mp4/m3u8
- 对 m3u8 额外解析：确认是有效 playlist（时长>0）

**触发**：命中任一路径且视频在播放 → 显示下载按钮。

#### 5.1.1 各站点解析器

`PageParser` 接口 + 按站点分发（`BrowserViewModel.parsers`）：

| 解析器 | 命中 | 做法 |
|---|---|---|
| `Hanime1DownloadParser` | `hanime1.me/watch?v=` | `/download?v=` 下载页表格解析（见上） |
| `PornhubParser` | pornhub 视频页 | 页面内 `mediaDefinitions[]` JSON / `qualityItems_N` JS 变量 |
| `YouTubeParser` | youtube.com、youtu.be、shorts/embed/live | InnerTube `POST /youtubei/v1/player`，多客户端依次尝试（见下） |

#### 5.1.2 YouTube 的取舍（重要）

调研结论（详见 [资源嗅探技术调研.md](资源嗅探技术调研.md) 第五节）：YouTube 现在的取流需要
**签名还原 + PO Token + `n` 参数（限速）解算**，三者都要跑播放器的混淆 JS。不引入 JS 引擎就
无法完整复刻。因此本项目：

**主用 Route A（请求级拦截）** —— WebView 里 YouTube 自己的播放器已经把这三件事都做完了，
它发出的 `googlevideo.com/videoplayback` 请求天然是可用直链，且 `n` 参数已解算（不会被限速）。
这是 Android 上唯一可靠且零依赖的路径。

**辅以 Route D（InnerTube 多客户端）** —— 用于「没播放也能列出清晰度」：
1. 取 `watch?v=<id>` 页面，从 `ytcfg` 里读 `INNERTUBE_API_KEY` / `VISITOR_DATA`；
2. 先试页面内嵌的 `ytInitialPlayerResponse`（快路径，省一次请求）；
3. 否则依次用 `VISIONOS → IOS → ANDROID` 请求 `/youtubei/v1/player`（**按成功率排序**）——
   这三个是 yt-dlp 客户端表里 `REQUIRE_JS_PLAYER = False` 的客户端，返回**明文 `url`**；
   `visionos` 还是其中唯一未配置 PO Token 策略的，故放最前；
4. 任一客户端拿到非空流清单即返回；遇到下架/需登录/年龄墙这类硬失败则提前结束轮询
   （换客户端不会变，避免白发请求）；全部失败则返回空列表，由 Route A 兜底。

解析时的三类硬性过滤：
- **带 `signatureCipher` 的格式直接放弃**（签名需 JS 还原）；
- **纯音频格式丢弃**（视频下载器不提供音频下载）；
- **非 `video/mp4` 容器丢弃**（导出链路固定写 `video/mp4` + `.mp4` 后缀，放行 webm 会产出
  扩展名与内容不符的文件 —— 见 `MediaUrlDetector.detect` 的同类处理）。

`signatureCipher` 解析、mimeType 分类、itag→清晰度、`ytcfg` 取值、花括号 JSON 提取都实现为
**纯函数**，可 JVM 单测；网络部分不做单测。

**已知能力边界**（写进文档而不是假装支持）：
- PO Token 无法生成 → Route D 对相当一部分视频拿不到直链，此时静默回退 Route A；
- **无封装器**：1080p 以上几乎只有 video-only 流，本工程不做音视频合流，因此这类格式
  **保留但显式标注「（无音轨）」**，让用户自己决定，而不是悄悄给一个没声音的文件；
- SABR（服务端自适应码率）走 UMP POST 协议，其 URL 不能直接 GET 下载，按候选筛掉；
- `range` 参数：`shouldInterceptRequest` 看到的分块 URL 会在入队前经
  `MediaUrlDetector.normalizeForDownload` 去掉**未被签名**（不在 `sparams` 中）的 `range`，
  否则同一个视频的不同片段会被当成多个资源，且只会下到一小段。

### 5.2 下载存储设计（私有暂存 + 公共 Download/ 导出）

```
mp4 多线程任务：
  分片下载 → 私有目录临时文件（真实路径，RandomAccessFile 随机写）
  ── 全部完成后 ──
  复制/移动到 MediaStore.Downloads
  → 用户可见路径: /Download/hanime1/<标题>_<清晰度>.mp4
  → 复制完成后删除私有暂存

m3u8 任务：
  TS 分片在私有目录下载 → concat 合并到私有 mp4 → 再导出到 Download/
```

**为什么这样设计：**
- 多线程分片需要 `RandomAccessFile.seek()` 随机写，必须落在真实文件路径（app 私有目录）
- Android 10+ 用 `MediaStore.Downloads` 写公共下载目录，**写入无需任何存储权限**
- MediaStore 的 content URI 不支持随机访问写入，不能直接当分片目标

**MediaStore 导出要点：**
```
ContentValues:
  DISPLAY_NAME  = "<标题>_<清晰度>.mp4"
  MIME_TYPE     = "video/mp4"
  RELATIVE_PATH = Environment.DIRECTORY_DOWNLOADS + "/hanime1"   // → /Download/hanime1/
  写入完成后 IS_PENDING = 0（原子可见）
```

### 5.3 下载引擎（多线程）

按源类型分两种任务：

**SingleFileTask（mp4，主路径）**
```
探测: GET Range: bytes=0-0 → 206(取 Content-Range 总长，支持分片) / 否则单线程整包
分片: 按线程数N均分，[start,end]，最后一片吃余数
并发: 每分片 Range 请求自己区间，RandomAccessFile.seek(start) 写入
      各分片区间不重叠 → 无需加锁；用 "rw" 而非 "rwd"（避免每写同步落盘拖垮并发）
断点: shards 表记录 start/end/finished，重启复用 finished 偏移续传
重试: 单分片失败退避重试 3 次，最终以 Failed 结果上报（不抛异常）
```

**M3U8Task（HLS）**
```
解析: master → 选最高带宽变体；media → 分片列表
      （M3U8Parser 解析 #EXT-X-KEY / #EXT-X-MAP / #EXT-X-BYTERANGE / #EXT-X-MEDIA-SEQUENCE）
解密: METHOD=AES-128 用 javax.crypto 做 AES-128-CBC 解密；IV 取显式值或由媒体序号推导
      METHOD=SAMPLE-AES 等不支持算法 → 显式失败（不产出无法播放的成品）
fMP4: #EXT-X-MAP 初始化段作为第 0 个分片参与拼接
并发: 信号量限制并发数，逐分片写独立文件（.tmp → 原子改名）
合并: 按序号字节级 concat → mp4
断点: 已存在的完整分片文件即视为完成，跳过
```

### 5.4 协程与并发控制

> 实现偏差：原方案用 `ThreadPoolExecutor`，落地改为**协程 + Semaphore**；线程池的容量约束
> 由「任务级限流 + 分片级信号量」两级共同承担，无需自管池。

- 任务级限流：`maxConcurrentTasks`（设置项，1~5）限制同时下载的任务数，超出置 `QUEUED` 排队，
  每有任务结束由 `DownloadManager.pumpQueue()` 自动补位。槽位在同步块内用一个只在
  「进入调度到写入 activeJobs」之间存活的 `starting` 集合预约，
  既关闭了「判定有空槽」到「实际占用槽位」之间的超限窗口，`starting.size + activeJobs.size`
  又恰好等于真实占用数（若用「直到任务结束才移除」的集合，同一 id 会被两个集合重复计数，
  并发数会静默退化成 1）
- 分片级并发：每个任务内部按 `threadCount`（1~32）用 `Semaphore` 限制同时进行的 Range 请求数
- OkHttp `maxRequestsPerHost` 提升到 64，否则默认 5 会把多线程压成五并发
- 进度上报：分片只累加共享计数器，独立 ticker 协程每 **300ms** 汇总一次，避免回调竞态
- 状态写入用 `_tasks.update { }`（CAS）而非「读 value → map → 写 value」，
  否则 ticker 的进度回调与 `runTask` 的状态迁移并发时会互相覆盖、静默丢失状态更新
- 前台服务的启停：启动由调度器负责（`startForegroundService`），**停止只由 `DownloadService`
  自己的收集器决定**（无存活任务即 `stopSelf`）。若调度器也并发判断「空闲则 stopService」，
  可能把刚被 enqueue 启动的服务杀掉
- `DownloadService.onCreate` 里会先 `DownloadManager.init(this)`：`START_STICKY` 下系统可能
  在没有 Activity 的情况下重建服务，不初始化就会出现「排队任务永不启动 + 服务立即自杀」

### 5.5 状态机

```
QUEUED → PENDING → PROBING → DOWNLOADING → EXPORTING → COMPLETED
   │         │         │            │
   │         │         │      ┌─────┴───────┐
   │         │         │   PAUSED(可续传)  FAILED(可重试)
   │         └─────────┴──→ CANCELED(删记录+删文件)
   └─ 排队中（等待并发槽位）
```
- `EXPORTING`：私有暂存已下载完，正在复制到公共 Download/
- 下载引擎返回 `DownloadOutcome`（Success / Stopped / Failed），**不抛异常**；
  由 `Stopped` 信号区分用户「暂停」与「取消」，避免网络失败被误判成取消而删档
- 进度/速度经 Flow 上报，ticker **节流 300ms**；下载列表订阅 StateFlow 实时刷新
- 停止语义：暂停=保留 DB 记录与已下载字节；取消=删记录+删私有暂存

### 5.6 后台下载

- **前台服务**（`dataSync` 类型）+ 通知：下载/导出中常驻通知栏，显示进度、暂停/取消按钮
- Android 13+ 请求 `POST_NOTIFICATIONS` 权限
- 导出阶段同样在前台服务内执行，保持可见

---

## 六、数据模型（原生 SQLite，库名 `hanime1.db`）

> 实现偏差：原方案用 Room，落地改为 `SQLiteOpenHelper`（见技术选型）。表结构与设计一致。
> `onUpgrade` 采用**增量 ALTER TABLE 补列**，不再 DROP 重建 —— 否则升级会清空用户下载记录与断点进度。

```sql
tasks:
  id TEXT PRIMARY KEY, url TEXT NOT NULL, title TEXT NOT NULL, quality TEXT,
  type TEXT NOT NULL,                    -- mp4 | m3u8
  thread_count INTEGER NOT NULL,
  source_page_url TEXT NOT NULL,          -- 防盗链 Referer 与溯源
  m3u8_url TEXT,                          -- m3u8 任务保留原始 playlist 地址（签名 URL 可重解析）
  total_bytes INTEGER NOT NULL DEFAULT 0,
  downloaded_bytes INTEGER NOT NULL DEFAULT 0,
  file_path TEXT,                         -- 私有暂存路径，导出后改写为 content:// URI
  state TEXT NOT NULL,                    -- DownloadState 名
  error TEXT, created_at INTEGER NOT NULL

shards:                                   -- mp4 分片进度（m3u8 以私有目录分片文件为断点依据）
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  task_id TEXT NOT NULL, idx INTEGER NOT NULL,
  start INTEGER NOT NULL, end INTEGER NOT NULL,
  finished INTEGER NOT NULL DEFAULT 0
```

设置：SharedPreferences（`hanime1_settings`）存 `thread_count`、`max_concurrent_tasks`、`initial_page`。

---

## 七、工程包结构

> 实际命名空间为 `com.videosniffer`（初版方案写的是 `com.hanime1`）。

```
com/videosniffer/
├─ MainActivity.kt               (底部导航容器；Fragment hide/show 保 WebView 状态)
├─ ui/
│  ├─ browser/  BrowserFragment + BrowserViewModel (嗅探汇总/去重去抖/并发上限)
│  ├─ download/ DownloadListFragment + DownloadAdapter (订阅 StateFlow 实时刷新)
│  └─ settings/ SettingsFragment
├─ sniff/
│  ├─ PageParser.kt              (站点解析器接口)
│  ├─ Hanime1DownloadParser.kt   (Route D 主通道：下载页表格解析)
│  ├─ PornhubParser.kt / YouTubeParser.kt
│  ├─ MediaUrlDetector.kt        (Route A：扩展名/Content-Type 判定)
│  ├─ DetectedMedia.kt / SnifferHttp.kt
├─ download/
│  ├─ DownloadManager.kt         (单例：排队限流/调度/状态流/持久化/前台服务)
│  ├─ DownloadTask.kt            (任务模型 + DownloadState)
│  ├─ DownloadOutcome.kt         (Success/Stopped/Failed + Stopped 停止信号)
│  ├─ ShardCalculator.kt
│  ├─ engine/ SingleFileDownloader.kt   (mp4 多线程分片)
│  ├─ hls/ M3U8Parser.kt · HlsCrypto.kt · M3U8Downloader.kt
│  └─ export/ MediaStoreExporter.kt
├─ data/  DownloadDbHelper.kt    (原生 SQLite)
└─ core/  SettingsStore.kt (SharedPreferences) · DownloadService.kt (前台服务)
```

---

## 八、依赖与权限

### 依赖清单（与 `app/build.gradle.kts` 一致）

| 依赖 | 用途 |
|---|---|
| `androidx.appcompat` / `material` / `core-ktx` | Activity、主题、`SwitchCompat`、FloatingActionButton、BottomNavigationView |
| `androidx.fragment-ktx` / `lifecycle-viewmodel-ktx` / `lifecycle-runtime-ktx` | Fragment、ViewModel、`repeatOnLifecycle` |
| `androidx.recyclerview` | 下载列表 |
| `okhttp` | Range 请求、HEAD 探测、页面抓取 |
| `kotlinx-coroutines-android` | 下载并发、状态流 |
| `jsoup` | hanime1 watch 页标题/下载页表格解析 |

> **落地偏差**：原方案的 `Room (ktx + compiler)` 与 `DataStore` 均未引入 —— 持久化改用原生
> `SQLiteOpenHelper`、设置改用 `SharedPreferences`（理由见「技术选型」）。这样也避开了 KSP，
> 在 AGP 9 内置 Kotlin 下编译更稳。测试依赖仅 `junit`（+ `espresso`/`androidx.junit` 供仪器测试）。

### 权限清单

| 权限 | 说明 |
|---|---|
| `INTERNET` | 联网（必须） |
| `FOREGROUND_SERVICE` | 后台下载服务 |
| `FOREGROUND_SERVICE_DATA_SYNC` | 下载型前台服务（Android 14+ 必须） |
| `POST_NOTIFICATIONS` | Android 13+ 下载进度通知，运行时请求 |
| `READ_EXTERNAL_STORAGE` | `maxSdkVersion="32"`，读取/打开共享存储视频 |
| `WRITE_EXTERNAL_STORAGE` | `maxSdkVersion="28"`，Android 10 以下旧设备（minSdk 29 实际不触发，保留兼容） |
| `READ_MEDIA_VIDEO` | Android 13+，读取公共媒体视频（若需导入/扫描已有文件才请求） |

> 说明：由于 `minSdk 29`，写公共 `Download/` 走 MediaStore **不申请任何存储权限**。后三个权限用于「读取/打开其他来源的视频」，按需运行时请求，不影响核心下载功能。

### AndroidManifest 权限声明示例

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission
    android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32" />
<uses-permission
    android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28" />
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
```

---

## 九、实现里程碑

| 阶段 | 内容 | 产出 | 状态 |
|---|---|---|---|
| M1 | 依赖/导航/WebView 浏览器 | 能正常浏览站点 | ✅ 已实现 |
| M2 | 嗅探引擎 + 下载按钮 + 质量选择 | 出现下载按钮并可选择清晰度 | ✅ 已实现 |
| M3 | SingleFile 多线程下载 + 断点续传 + 进度流 | mp4 多线程可下载/续传 | ✅ 已实现 |
| M4 | M3U8 分段下载 + 解密 + 合并 | HLS（含 AES-128 加密流）可下载 | ✅ 已实现 |
| M5 | 下载列表页 + 前台服务 + 设置页 + MediaStore 导出 | 完整闭环 | ✅ 已实现 |

**实现后加固（v1.1 修复项）**

| 问题 | 处理 |
|---|---|
| m3u8 加密流未解密，成品无法播放 | 新增 `HlsCrypto` + `M3U8Parser`，支持 AES-128-CBC；不支持算法显式失败 |
| m3u8 缺 `#EXT-X-MAP`，fMP4 流不可播 | 初始化段作为第 0 个分片参与拼接 |
| 网络失败被误判为「取消」而删除已下载数据 | 引擎改为返回 `DownloadOutcome`，用 `Stopped` 信号区分暂停/取消/失败 |
| `maxConcurrentTasks` 设置项从未生效 | `DownloadManager.pumpQueue()` 按上限排队补位 |
| 下载列表进度与速度静止不动（且暂停后按钮不变「继续」、通知百分比不动） | **根因**：`DownloadTask` 是 data class，旧 `updateTask` 把已发布的**同一实例**放回列表，`List.equals` 比的是同一对象 → StateFlow 判定「值没变」而永不广播（订阅写法本身没问题）。修复：`_tasks` 只存不可变快照，引擎用私有副本 `work`，发布一律 `copy()`/`mutateTask` 替换 |
| 暂停后按钮停在「暂停」（自身回归） | 500ms 轮询曾加「仅当有活跃任务时刷新」的条件，而暂停/完成/失败恰好是活跃数为 0 的时刻 → 恰好漏刷。改为无条件轮询 |
| 暂停被误判成「失败」 | `job.cancel()` 后引擎 `ensureActive()` 抛 `CancellationException`，被 `catch (Exception)` 当成下载失败。新增 `catch (CancellationException)` → 按停止处理并重新抛出；`finishFailed` 再兜一层「已请求停止则不覆盖」 |
| YouTube 解析形同虚设（只抓 `ytInitialPlayerResponse`，拿不到 `streamingData` 就返回空） | 改用 InnerTube `POST /youtubei/v1/player`，按成功率依次尝试 `VISIONOS`/`IOS`/`ANDROID` 三个免 JS 客户端（返回明文 url），页面内嵌响应仅作快路径；下架/需登录/年龄墙这类硬失败提前终止轮询 |
| YouTube 列出「音频128k」这类伪视频选项、以及无声的纯视频 | 纯音频格式丢弃；纯视频格式保留但标注「（无音轨）」；同清晰度优先给合流格式 |
| 下载直链/文件名解析不出清晰度（`qualityFromFileName` 恒返回 null） | `qualitySuffixRegex` 既**没有捕获组**（而取值用的是 `groupValues[1]`，真匹配上会 `IndexOutOfBoundsException`），又用 `$` 卡死结尾，带 `.mp4` 的文件名永远不匹配。改为 `-(\d+)p(?:\.[A-Za-z0-9]{1,5})?$`（补捕获组 + 扩展名可选）；顺带让 `qualityFromUrl` 改用纯字符串取路径末段，不再依赖 `android.net.Uri`（原本在 JVM 单测里必然失败） |
| YouTube 分块拉流的 `range=` URL 被当成独立资源且只能下到一小段 | 新增 `MediaUrlDetector.normalizeForDownload`：`range` 不在 `sparams`（未被签名）时剔除，`isVideoCandidate` 也不再排除 ranged URL |
| webm 格式会被导出成 `.mp4`（扩展名与内容不符） | 解析与嗅探统一只接受 `video/mp4` 容器；webm 明确不支持 |
| 布局硬编码 100/125dp 顶距浪费屏幕 | 去掉魔法值，改由 `fitsSystemWindows` 处理系统栏；主题改 NoActionBar |
| 设置页改「初始网站」需重启才生效 | `BrowserFragment.onResume` 检测变更并立即切换 |
| 嗅探 HEAD 请求无去重无上限 | 去重集合 + 600ms 批量 + 并发信号量（上限 4） |
| 数据库升级会清空下载记录 | `onUpgrade` 改为增量补列 |
| 清晰度对话框只显示「清晰度 · 大小 · 格式」，认不出是哪个视频 | 改为「作品名 · 清晰度 · 大小 · 格式」，标题取 watch 页 `<h1>`/`<title>` |
| 下载候选里混入 0.1~0.7 MB 的广告/预告短 mp4 | 已知大小 < 1 MB 的候选直接过滤，全被过滤时给出提示 |

---

## 十、风险与对策

| 风险 | 对策 |
|---|---|
| hanime1.me 页面结构改版 | 主通道解析失效时辅通道(请求级嗅探)兜底 |
| 防盗链（无 Referer 拒绝） | 下载请求携带页面 Referer + UA |
| 频率限制 | 任务间随机延迟 0.9~3s；限流队列 |
| 签名 URL 过期 | m3u8 解析后尽快下载；失败重新解析 |
| 导出复制阶段耗时长（大文件） | 状态机区分「下载中」与「导出中」两段进度；前台服务保持可见 |
| WebView 内存 | 监听 onTrimMemory 释放页面缓存 |

---

## 附：免责声明

> hanime 内容属成人向且多为版权内容，本方案仅供学习研究参考。自用下载与公开分发是两回事，落地时注意遵守当地法律与合理使用边界。
