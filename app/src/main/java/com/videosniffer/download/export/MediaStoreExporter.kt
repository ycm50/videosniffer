package com.videosniffer.download.export

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream

/**
 * 将私有目录下载完成的文件导出到公共 Download/ 目录（/Download/hanime1/）。
 * Android 10+ 经 MediaStore 写入公共下载，无需任何存储权限。
 */
object MediaStoreExporter {

    /**
     * @return 成功返回导出的 content Uri 字符串；失败返回 null
     */
    fun exportToDownloads(context: Context, src: File, displayName: String): String? {
        if (!src.exists()) return null
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/hanime1"
            )
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return null

        return try {
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(src).use { input ->
                    input.copyTo(out, 256 * 1024)
                }
            } ?: return null

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)

            src.delete()
            uri.toString()
        } catch (_: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }
}
