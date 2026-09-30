package com.zyd.browser

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

data class BrowserEntry(
    val id: Long,
    val title: String,
    val url: String,
    val timestamp: Long,
    val favicon: ByteArray? = null
)

class BrowserDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE bookmarks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                url TEXT NOT NULL UNIQUE,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                url TEXT NOT NULL UNIQUE,
                visited_at INTEGER NOT NULL,
                favicon BLOB
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX history_visited_at ON history(visited_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE history ADD COLUMN favicon BLOB")
        }
    }

    fun addBookmark(title: String, url: String) {
        writableDatabase.insertWithOnConflict(
            TABLE_BOOKMARKS,
            null,
            ContentValues().apply {
                put("title", normalizedTitle(title, url))
                put("url", url)
                put("created_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun removeBookmark(url: String) {
        writableDatabase.delete(TABLE_BOOKMARKS, "url = ?", arrayOf(url))
    }

    fun isBookmarked(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        readableDatabase.query(
            TABLE_BOOKMARKS,
            arrayOf("id"),
            "url = ?",
            arrayOf(url),
            null,
            null,
            null,
            "1"
        ).use { return it.moveToFirst() }
    }

    fun recordVisit(title: String, url: String, favicon: Bitmap? = null) {
        val values = ContentValues().apply {
            put("title", normalizedTitle(title, url))
            put("url", url)
            put("visited_at", System.currentTimeMillis())
            favicon?.let { put("favicon", encodeFavicon(it)) }
        }
        val updated = writableDatabase.update(TABLE_HISTORY, values, "url = ?", arrayOf(url))
        if (updated == 0) writableDatabase.insert(TABLE_HISTORY, null, values)
        writableDatabase.execSQL(
            "DELETE FROM history WHERE id NOT IN " +
                "(SELECT id FROM history ORDER BY visited_at DESC LIMIT $MAX_HISTORY)"
        )
    }

    fun updateHistoryIcon(url: String, favicon: Bitmap) {
        writableDatabase.update(
            TABLE_HISTORY,
            ContentValues().apply { put("favicon", encodeFavicon(favicon)) },
            "url = ?",
            arrayOf(url)
        )
    }

    fun getBookmarks(): List<BrowserEntry> = queryEntries(
        table = TABLE_BOOKMARKS,
        timeColumn = "created_at",
        orderBy = "created_at DESC"
    )

    fun getHistory(): List<BrowserEntry> = queryEntries(
        table = TABLE_HISTORY,
        timeColumn = "visited_at",
        orderBy = "visited_at DESC"
    )

    fun deleteEntry(mode: String, id: Long) {
        val table = if (mode == LibraryActivity.MODE_BOOKMARKS) TABLE_BOOKMARKS else TABLE_HISTORY
        writableDatabase.delete(table, "id = ?", arrayOf(id.toString()))
    }

    fun clearHistory() {
        writableDatabase.delete(TABLE_HISTORY, null, null)
    }

    private fun queryEntries(table: String, timeColumn: String, orderBy: String): List<BrowserEntry> {
        val entries = mutableListOf<BrowserEntry>()
        val includeFavicon = table == TABLE_HISTORY
        readableDatabase.query(
            table,
            if (includeFavicon) {
                arrayOf("id", "title", "url", timeColumn, "favicon")
            } else {
                arrayOf("id", "title", "url", timeColumn)
            },
            null,
            null,
            null,
            null,
            orderBy
        ).use { cursor ->
            while (cursor.moveToNext()) {
                entries += BrowserEntry(
                    id = cursor.getLong(0),
                    title = cursor.getString(1),
                    url = cursor.getString(2),
                    timestamp = cursor.getLong(3),
                    favicon = if (includeFavicon && !cursor.isNull(4)) cursor.getBlob(4) else null
                )
            }
        }
        return entries
    }

    private fun normalizedTitle(title: String, url: String): String =
        title.trim().ifEmpty { url }

    private fun encodeFavicon(source: Bitmap): ByteArray {
        val icon = if (source.width > FAVICON_SIZE || source.height > FAVICON_SIZE) {
            Bitmap.createScaledBitmap(source, FAVICON_SIZE, FAVICON_SIZE, true)
        } else {
            source
        }
        return ByteArrayOutputStream().use { output ->
            icon.compress(Bitmap.CompressFormat.PNG, 100, output)
            if (icon !== source) icon.recycle()
            output.toByteArray()
        }
    }

    companion object {
        private const val DATABASE_NAME = "browser.db"
        private const val DATABASE_VERSION = 2
        private const val TABLE_BOOKMARKS = "bookmarks"
        private const val TABLE_HISTORY = "history"
        private const val MAX_HISTORY = 500
        private const val FAVICON_SIZE = 64
    }
}
