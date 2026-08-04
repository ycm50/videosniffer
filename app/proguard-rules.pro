# hanime1 R8 混淆/压缩规则

# Fragment 子类：FragmentManager 可能按类名实例化，避免被混淆
-keep public class * extends androidx.fragment.app.Fragment

# Service 子类（Manifest 已声明会被自动保留，此处兜底）
-keep public class * extends android.app.Service

# 下载/嗅探核心类保留类名（便于日志与崩溃定位，可选）
-keep class com.videosniffer.download.** { *; }
-keep class com.videosniffer.sniff.** { *; }

# OkHttp / okio 自带 consumer rules，无需额外 keep；忽略缺失类警告
-dontwarn okhttp3.**
-dontwarn okio.**
