# hanime1 App 设计方案

> 版本：v1.0 · 2026-08-03
> 依据：《资源嗅探技术调研.md》《多线程下载技术调研.md》
> 状态：待实现（M1 起按里程碑推进）

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

1. 主界面是 **系统 WebView 浏览器**，可正常浏览 `hanime1.me` 及从页内点开的链接
2. 播放视频时自动嗅探视频资源，弹出**下载按钮**（含清晰度选择）
3. 底部导航第二个 Tab 是**独立下载管理界面**
4. 下载采用**多线程分片/分段**，支持**断点续传**，**线程数可配置**
5. 下载完成后的视频放在 **公共 Download/ 目录**（`/Download/hanime1/`）

---

## 二、技术选型

| 项 | 选择 | 理由 |
|---|---|---|
| 浏览器内核 | **系统 WebView**（`android.webkit.WebView`） | 需求是浏览时嗅探；不引入 XWalkView/Crosswalk；minSdk 29 下系统 WebView 已支持 `shouldInterceptRequest`（API 21+）与 JS 注入 |
| UI | XML + Material（现有依赖，不引入 Compose） | 项目未配 Compose；WebView 为主，XML 足够，轻量 |
| 网络 | OkHttp | Range 请求、拦截器、响应头读取成熟 |
| 持久化 | Room | 断点续传需 SQLite 存任务与分片状态 |
| 并发 | kotlinx-coroutines + ThreadPoolExecutor | 下载引擎用线程池，UI 用协程 |
| 播放 | 系统 WebView 内嵌播放 | 需求是「浏览时嗅探」，不另做播放器 |
| m3u8 合并 | MPEG-TS 字节级 concat（首选）/ ffmpeg 可选 | TS 分段大多可无损拼接，免原生依赖 |

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

单向数据流：UI → ViewModel → DownloadManager(状态流) → Room 持久化 → 恢复。

---

## 四、页面与交互设计

### 4.1 浏览器页（默认页）

- 顶部：地址栏 + 前进/后退/刷新
- 中部：系统 WebView（`hanime1.me` 主页进入；页内链接在**同一 WebView 打开**，拦截外链）
- WebView 配置：启用 JS、DOM Storage、混合内容按需；`WebViewClient` 控制页内导航与请求回调
- 浮动下载按钮：嗅探到视频资源时**从底部滑出**，点击弹出质量选择 Dialog（含**每任务线程数**）
- 播放检测：注入 JS 探测 `<video>` 元素播放状态（`play` 事件 / `video.paused`），确认在播放后高亮按钮

### 4.2 下载列表页

- RecyclerView 列表：标题 / 清晰度 / 文件大小 / 进度条 / 实时速度 / 状态徽标
- 点击任务展开操作：**暂停 / 继续 / 取消 / 删除 / 重试 / 打开文件**
- 顶部聚合栏：总进度、活跃下载数
- 状态过滤 Tab（全部/下载中/已完成/失败）
- 已完成任务通过 MediaStore 返回的 content URI 打开/分享（无需额外权限）

### 4.3 设置页

- **全局线程数滑块（1~16，默认 4）**，存 DataStore，新任务生效
- 下载目录说明（固定导出到 `Download/hanime1/`）
- 同时下载任务上限
- 清除已完成记录

---

## 五、核心功能设计

### 5.1 资源嗅探（双通道）

针对 hanime1.me 特性（调研结论：**不需要通用嗅探，解析下载页最稳定**）。

**主通道 —— 下载页解析（Route D）**
1. `WebViewClient.onPageFinished` / URL 变化时判断是否命中 `hanime1.me/watch?v=<vid>`
2. 命中则后台请求 `hanime1.me/download?v=<vid>`，解析 `table.download-table` 下 `<a download>` 标签
3. 正则 `[^/]+-\d+p` 提取清晰度 → 得到 `{1080p: url, 720p: url, ...}` mp4 直链
4. HEAD 请求补全 `Content-Length` 与 `Content-Type`（octet-stream 按 mp4 处理）

**辅通道 —— 请求级拦截（Route A，兜底）**
- `shouldInterceptRequest` / `onLoadStarted` 收集所有请求 URL
- 后台 HEAD 验证扩展名 + Content-Type + Content-Length，识别 mp4/m3u8
- 对 m3u8 额外解析：确认是有效 playlist（时长>0）

**触发**：命中任一路径且视频在播放 → 显示下载按钮。

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

**SingleFileTask（mp4，hanime1 主路径）**
```
探测: GET Range: bytes=0- → 206(支持Range) / 200(不支持→单线程)
分片: 按线程数N均分，[start,end]，最后一片吃余数
并发: 每线程 Range 请求自己区间，RandomAccessFile.seek(start) 写入
      各线程区间不重叠 → 无需加锁
断点: 线程表记录 start/end/finished，重启复用 finished 偏移续传
```

**M3U8Task（兜底流媒体）**
```
解析: playlist → .ts segment URL 列表（含 #EXTINF 时长）
并发: 每批取 N 个 segment 并行下载到独立临时文件
合并: TS 字节级 concat → mp4（或 ffmpeg）
断点: 记录已完成 segment，跳过已下载
```

### 5.4 线程池与并发控制

- 全局线程池：`ThreadPoolExecutor(cpuCores, cpuCores*2, 60s, LinkedBlockingQueue)`
- 每个任务占用 `config.threadNum` 个并发（单文件=分片数；m3u8=并发分段数）
- 多任务并行受池容量约束；任务级限流：最多 N 个任务同时下载，超出排队（借鉴 devaige `DLManager`）
- 线程命名 `DlTask#n / DlSeg#n` 便于日志定位

### 5.5 状态机

```
PENDING → PROBING → DOWNLOADING → EXPORTING → COMPLETED
              │          │
              │      ┌───┴────────┐
              │   PAUSED(可续传)  FAILED(可重试)
              └─ CANCELED(删记录+删文件)
```
- `EXPORTING`：私有暂存已下载完，正在复制到公共 Download/（对应「导出中」阶段）
- 全部子任务完成才置 COMPLETED（借鉴 Aspsine 聚合逻辑）
- 进度/速度经 Handler 主线程回调，**节流 1s 上报一次**避免刷爆 UI
- 停止语义：暂停=保留 DB 记录与已下载字节；取消=删记录+删私有暂存

### 5.6 后台下载

- **前台服务**（`dataSync` 类型）+ 通知：下载/导出中常驻通知栏，显示进度、暂停/取消按钮
- Android 13+ 请求 `POST_NOTIFICATIONS` 权限
- 导出阶段同样在前台服务内执行，保持可见

---

## 六、数据模型（Room）

```sql
TaskEntity:
  id TEXT PK, url TEXT, title TEXT, quality TEXT, type TEXT(mp4|m3u8),
  state TEXT, threadCount INT, totalBytes LONG, downloadedBytes LONG,
  filePath TEXT, m3u8Url TEXT?, createTime LONG

SegmentEntity:            -- mp4 分片 与 m3u8 分段 通用
  id TEXT PK, taskId TEXT FK, index INT, url TEXT,
  start LONG, end LONG, finished LONG, filePath TEXT
```

设置：DataStore 存 `threadCount`, `maxConcurrentTasks`。

---

## 七、工程包结构

```
com.hanime1/
├─ MainActivity.kt               (底部导航容器)
├─ ui/
│  ├─ browser/  BrowserFragment + BrowserViewModel + WebClient
│  ├─ download/ DownloadListFragment + DownloadViewModel
│  └─ settings/ SettingsFragment + SettingsViewModel
├─ sniff/
│  ├─ MediaSniffer.kt            (双通道调度)
│  ├─ Hanime1DownloadParser.kt   (下载页解析)
│  └─ MediaUrlDetector.kt        (扩展名/Content-Type 判定)
├─ download/
│  ├─ DownloadManager.kt         (单例：调度/限流/状态流)
│  ├─ DownloadTask.kt / DownloadStatus.kt
│  ├─ engine/ SingleFileDownloader.kt / M3U8Downloader.kt
│  └─ ShardCalculator.kt
├─ data/
│  ├─ db/ (Room: TaskDao, SegmentDao, Entity)
│  └─ repo/ DownloadRepository.kt
├─ core/
│  ├─ SettingsStore.kt           (DataStore)
│  └─ DownloadService.kt         (前台服务)
```

---

## 八、依赖与权限

### 新增依赖

- OkHttp
- Room (ktx + compiler)
- kotlinx-coroutines-android
- lifecycle-viewmodel-ktx
- recyclerview（如需）

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

| 阶段 | 内容 | 产出 |
|---|---|---|
| M1 | 依赖/导航/WebView 浏览器 | 能浏览 hanime1.me |
| M2 | 嗅探引擎 + 下载按钮 + 质量选择 | 播放时出现下载按钮 |
| M3 | SingleFile 多线程下载 + 断点续传 + 进度流 | mp4 多线程可下载 |
| M4 | M3U8 分段下载 + 合并 | 兜底流媒体 |
| M5 | 下载列表页 + 前台服务 + 设置页 + MediaStore 导出 | 完整闭环 |

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
