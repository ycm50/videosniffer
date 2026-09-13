# VideoSniffer（嗅探下载）

一个 Android 原生「**内置浏览器 + 资源嗅探 + 多线程断点续传下载**」App。
在 WebView 里正常浏览站点，播放页/下载页被识别后一键把视频存到公共 `Download/` 目录。

- 语言：Kotlin（纯原生，无 Compose / 无 Room / 无 KSP）
- minSdk 29，targetSdk 36，AGP 9 + Gradle 9
- 关键依赖仅 3 个：OkHttp、Jsoup、kotlinx-coroutines

## 功能

| 模块 | 说明 |
|---|---|
| 浏览器 | 系统 WebView，地址栏/前进后退/刷新，起始站点可在设置里改；可切「电脑版网页」（桌面 UA + 桌面视口） |
| 资源嗅探 | **双通道**：页面解析（Route D，主）+ 请求级拦截（Route A，兜底） |
| 站点解析器 | hanime1.me（下载页表格）、Pornhub（flashvars JSON）、YouTube（InnerTube 免 JS 多客户端 + 请求级拦截兜底） |
| mp4 下载 | Range 探测 → 按线程数分片并发 → `RandomAccessFile` 区间写；断点续传 |
| m3u8 下载 | master/media 解析、`#EXT-X-MAP`（fMP4）、`#EXT-X-BYTERANGE`、**AES-128 解密** |
| 任务管理 | 并发上限排队、暂停/继续/取消/重试/删除、失败原因展示 |
| 后台下载 | `dataSync` 前台服务 + 进度通知 |
| 导出 | MediaStore 写入 `/Download/hanime1/`，minSdk 29 下**无需存储权限** |

## 目录结构

```
app/src/main/java/com/videosniffer/
├─ MainActivity.kt                 底部导航容器（Fragment hide/show 保 WebView 状态）
├─ ui/
│  ├─ browser/   BrowserFragment + BrowserViewModel（嗅探汇总、去重去抖）
│  ├─ download/  DownloadListFragment（订阅 StateFlow 实时刷新）+ DownloadAdapter
│  └─ settings/  SettingsFragment（线程数 / 并发任务数 / 初始网站 / 电脑版网页）
├─ sniff/
│  ├─ PageParser.kt                 站点解析器接口
│  ├─ Hanime1DownloadParser.kt      下载页表格解析（Route D 主通道）
│  ├─ PornhubParser.kt / YouTubeParser.kt
│  ├─ MediaUrlDetector.kt           扩展名 + Content-Type 判定（Route A）
│  ├─ DetectedMedia.kt / SnifferHttp.kt
│  └─ ...
├─ download/
│  ├─ DownloadManager.kt            单例：排队限流/调度/状态流/持久化/前台服务
│  ├─ DownloadTask.kt               任务模型 + 状态机
│  ├─ DownloadOutcome.kt            下载结果（Success/Stopped/Failed）与 Stopped 信号
│  ├─ ShardCalculator.kt            分片区间计算
│  ├─ engine/ SingleFileDownloader.kt   mp4 多线程分片下载
│  ├─ hls/    M3U8Parser.kt · HlsCrypto.kt · M3U8Downloader.kt
│  └─ export/ MediaStoreExporter.kt
├─ core/  SettingsStore.kt · DownloadService.kt
└─ data/  DownloadDbHelper.kt       原生 SQLite（tasks + shards）
```

设计与调研文档见 [`docs/`](docs/)：
[App设计方案.md](docs/App设计方案.md) ·
[资源嗅探技术调研.md](docs/资源嗅探技术调研.md) ·
[多线程下载技术调研.md](docs/多线程下载技术调研.md) ·
[变更验证清单.md](docs/变更验证清单.md)

## 构建

```bash
# 1. 指向本机 Android SDK（该文件被 .gitignore 忽略）
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# 2. debug
./gradlew assembleDebug

# 3. release（需环境变量 KEYSTORE_PATH 指向签名库）
./gradlew assembleRelease
```

CI 见 [`.github/workflows/release.yml`](.github/workflows/release.yml)：手动触发后自动递增版本号
（v1.0 → v1.1 → …）、构建并发布 Release APK。

## 测试

```bash
./gradlew testDebugUnitTest
```

单元测试聚焦**纯逻辑**（不依赖 Android 框架，可在 JVM 直接跑），共 7 个测试类：

- `M3U8ParserTest` — master/media playlist、`EXT-X-KEY`/`MAP`/`BYTERANGE`、相对 URL 解析
- `HlsCryptoTest` — IV 解析与推导、AES-128-CBC 加解密往返、不支持算法的拒绝
- `ShardCalculatorTest` — 分片区间覆盖性与边界、`index` 线程数编码
- `MediaUrlDetectorTest` — Content-Type 映射、扩展名提取、清晰度提取
- `Hanime1DownloadParserTest` — 文件名去扩展名/去清晰度后缀、网页标题剥站点尾巴
- `DownloadTaskPublishTest` — 钉住「发布不可变快照」的约定（StateFlow 相等性判重那一课）
- `YouTubeParserTest` — videoId 提取、mimeType 分类、itag→清晰度、`signatureCipher`/花括号 JSON/`ytcfg` 解析、`range` 归一化

> 依赖 `android.net.Uri` 的判定（`MediaUrlDetector.isVideoCandidate`、`detect`、`YouTubeParser.matches`）
> 需在仪器测试（`androidTest`）中验证，因此上述纯逻辑都已拆成不碰 `Uri` 的函数。

## 实现要点

- **状态流只放不可变快照**：`DownloadTask` 是 data class，而 `StateFlow` 按 `==` 判重。
  早期实现把**已发布的同一实例**就地改完再放回列表，`List.equals` 比的是同一个对象 → 恒相等
  → 订阅者永远收不到更新（表现为：进度/速度静止、暂停后按钮不变「继续」、通知百分比不动，
  只有手动点「刷新」直读 `tasks.value` 才看得见）。现在 `_tasks` 只放快照，引擎在私有副本
  `work` 上改，发布一律 `copy()` / `mutateTask` 替换。
- **并发上限**：`DownloadManager.pumpQueue()` 按「同时下载任务数」补位，超出置 `QUEUED` 排队。
  （该设置曾被存储但从未生效，现已落实。）
- **断点续传校验**：分片表里的 `index` 编码了创建时的线程数。若用户在续传前改了线程数，
  沿用旧分片表会漏下载剩余区间，因此会校验线程数与总长，不匹配就重新规划分片。
- **结果驱动而非异常驱动**：下载引擎返回 `DownloadOutcome`，由 `Stopped` 信号区分
  「暂停 / 取消 / 失败」。这样网络抖动不会被误判成用户取消而删档。
- **YouTube 以请求级嗅探为主**：Route A 拿到的是 YouTube 自己播放器发出的 `videoplayback`，
  签名、PO Token、`n` 限速参数都已由它处理；Route D（InnerTube 免 JS 客户端）只用于
  「没播放也能列出清晰度」。带 `signatureCipher` 的格式一律放弃，纯音频与非 mp4 容器丢弃，
  纯视频保留但标注「（无音轨）」；未签名的 `range` 参数会被归一化掉，避免只下到一小段。
- **进度方案**：分片只累加共享计数器，由独立 ticker 协程每 300ms 上报，避免回调竞态。
- **OkHttp 调优**：`maxRequestsPerHost` 提到 64，否则默认为 5 会把多线程压成单机五连。
- **随机写**：分片用 `RandomAccessFile("rw")` 而非 `"rwd"`，避免每写同步落盘拖垮并发。
- **加密流**：`SAMPLE-AES` 等不支持算法会**显式失败**，不会静默产出无法播放的成品。

## 免责声明

本项目仅供技术学习与研究。请遵守所在地区法律法规与站点服务条款，勿用于侵权或公开分发。
