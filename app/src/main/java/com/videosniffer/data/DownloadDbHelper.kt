package com.videosniffer.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.videosniffer.download.DownloadState
import com.videosniffer.download.DownloadTask
import com.videosniffer.download.Shard

/**
 * SQLite 持久化：tasks（任务）+ shards（分片/分段进度）。
 * 采用原生 SQLiteOpenHelper，避免引入 KSP 注解处理，保证 AGP 9 内置 Kotlin 下编译稳定。
 */
class DownloadDbHelper(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE tasks (
                id TEXT PRIMARY KEY,
                url TEXT NOT NULL,
                title TEXT NOT NULL,
                quality TEXT,
                type TEXT NOT NULL,
                thread_count INTEGER NOT NULL,
                source_page_url TEXT NOT NULL,
                m3u8_url TEXT,
                total_bytes INTEGER NOT NULL DEFAULT 0,
                downloaded_bytes INTEGER NOT NULL DEFAULT 0,
                file_path TEXT,
                state TEXT NOT NULL,
                error TEXT,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE shards (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                task_id TEXT NOT NULL,
                idx INTEGER NOT NULL,
                start INTEGER NOT NULL,
                end INTEGER NOT NULL,
                finished INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }

    /**
     * 增量升级：只补齐缺失的列，**不清空既有数据**。
     * 旧实现直接 DROP 两张表重建，会让用户所有下载记录与断点进度在版本升级后丢失。
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        val taskColumns = existingColumns(db, T_TASKS)
        val shardColumns = existingColumns(db, T_SHARDS)

        // 旧库没有这两张表时（异常场景）直接按新结构建
        if (taskColumns.isEmpty() && shardColumns.isEmpty()) {
            onCreate(db)
            return
        }

        addColumnIfMissing(db, T_TASKS, taskColumns, "m3u8_url", "TEXT")
        addColumnIfMissing(db, T_TASKS, taskColumns, "total_bytes", "INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(db, T_TASKS, taskColumns, "downloaded_bytes", "INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(db, T_TASKS, taskColumns, "file_path", "TEXT")
        addColumnIfMissing(db, T_TASKS, taskColumns, "error", "TEXT")
        addColumnIfMissing(db, T_TASKS, taskColumns, "created_at", "INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(db, T_SHARDS, shardColumns, "finished", "INTEGER NOT NULL DEFAULT 0")
    }

    private fun existingColumns(db: SQLiteDatabase, table: String): Set<String> {
        val columns = mutableSetOf<String>()
        runCatching {
            db.rawQuery("PRAGMA table_info($table)", null).use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(c.getColumnIndexOrThrow("name"))
                    columns.add(name)
                }
            }
        }
        return columns
    }

    private fun addColumnIfMissing(
        db: SQLiteDatabase,
        table: String,
        existing: Set<String>,
        column: String,
        definition: String
    ) {
        if (existing.isEmpty() || existing.contains(column)) return
        runCatching { db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition") }
    }

    // ---------- tasks ----------

    fun insertTask(task: DownloadTask) {
        writableDatabase.insertOrThrow(T_TASKS, null, taskToValues(task))
    }

    fun updateTask(task: DownloadTask) {
        writableDatabase.update(T_TASKS, taskToValues(task), "id=?", arrayOf(task.id))
    }

    fun deleteTask(taskId: String) {
        writableDatabase.delete(T_TASKS, "id=?", arrayOf(taskId))
        writableDatabase.delete(T_SHARDS, "task_id=?", arrayOf(taskId))
    }

    fun loadTasks(): List<DownloadTask> {
        val list = mutableListOf<DownloadTask>()
        readableDatabase.query(T_TASKS, null, null, null, null, null, "created_at ASC").use { c ->
            while (c.moveToNext()) {
                list.add(taskFromCursor(c))
            }
        }
        return list
    }

    fun loadTask(taskId: String): DownloadTask? {
        return readableDatabase.query(T_TASKS, null, "id=?", arrayOf(taskId), null, null, null)
            .use { c -> if (c.moveToFirst()) taskFromCursor(c) else null }
    }

    // ---------- shards ----------

    fun saveShards(taskId: String, shards: List<Shard>) {
        val db = writableDatabase
        db.delete(T_SHARDS, "task_id=?", arrayOf(taskId))
        shards.forEach { shard ->
            db.insert(
                T_SHARDS, null,
                ContentValues().apply {
                    put("task_id", taskId)
                    put("idx", shard.index)
                    put("start", shard.start)
                    put("end", shard.end)
                    put("finished", shard.finished)
                }
            )
        }
    }

    fun loadShards(taskId: String): List<Shard>? {
        val list = mutableListOf<Shard>()
        readableDatabase.query(T_SHARDS, null, "task_id=?", arrayOf(taskId), null, null, "idx ASC")
            .use { c ->
                while (c.moveToNext()) {
                    list.add(
                        Shard(
                            index = c.getInt(c.getColumnIndexOrThrow("idx")),
                            start = c.getLong(c.getColumnIndexOrThrow("start")),
                            end = c.getLong(c.getColumnIndexOrThrow("end")),
                            finished = c.getLong(c.getColumnIndexOrThrow("finished"))
                        )
                    )
                }
            }
        return list.takeIf { it.isNotEmpty() }
    }

    fun updateShard(taskId: String, shard: Shard) {
        writableDatabase.update(
            T_SHARDS,
            ContentValues().apply { put("finished", shard.finished) },
            "task_id=? AND idx=?",
            arrayOf(taskId, shard.index.toString())
        )
    }

    // ---------- mapping ----------

    private fun taskToValues(task: DownloadTask): ContentValues {
        return ContentValues().apply {
            put("id", task.id)
            put("url", task.url)
            put("title", task.title)
            put("quality", task.quality)
            put("type", task.type)
            put("thread_count", task.threadCount)
            put("source_page_url", task.sourcePageUrl)
            put("m3u8_url", task.m3u8Url)
            put("total_bytes", task.totalBytes)
            put("downloaded_bytes", task.downloadedBytes)
            put("file_path", task.filePath)
            put("state", task.state.name)
            put("error", task.error)
            put("created_at", task.createdAt)
        }
    }

    private fun taskFromCursor(c: android.database.Cursor): DownloadTask {
        return DownloadTask(
            id = c.getString(c.getColumnIndexOrThrow("id")),
            url = c.getString(c.getColumnIndexOrThrow("url")),
            title = c.getString(c.getColumnIndexOrThrow("title")),
            quality = c.getString(c.getColumnIndexOrThrow("quality")),
            type = c.getString(c.getColumnIndexOrThrow("type")),
            threadCount = c.getInt(c.getColumnIndexOrThrow("thread_count")),
            sourcePageUrl = c.getString(c.getColumnIndexOrThrow("source_page_url")),
            m3u8Url = c.getString(c.getColumnIndexOrThrow("m3u8_url")),
            totalBytes = c.getLong(c.getColumnIndexOrThrow("total_bytes")),
            downloadedBytes = c.getLong(c.getColumnIndexOrThrow("downloaded_bytes")),
            filePath = c.getString(c.getColumnIndexOrThrow("file_path")),
            state = runCatching { DownloadState.valueOf(c.getString(c.getColumnIndexOrThrow("state"))) }
                .getOrDefault(DownloadState.PENDING),
            error = c.getString(c.getColumnIndexOrThrow("error")),
            createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"))
        )
    }

    companion object {
        private const val DB_NAME = "hanime1.db"
        private const val DB_VERSION = 1
        private const val T_TASKS = "tasks"
        private const val T_SHARDS = "shards"
    }
}
