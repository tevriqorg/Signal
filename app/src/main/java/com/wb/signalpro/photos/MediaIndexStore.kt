package com.wb.signalpro.photos

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.wb.signalpro.data.model.MediaId
import com.wb.signalpro.data.model.MediaItem
import com.wb.signalpro.data.model.MediaType
import com.wb.signalpro.data.repository.MediaRepository

internal class MediaIndexStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION), MediaRepository {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE media_items (
                local_id TEXT PRIMARY KEY,
                media_store_id INTEGER NOT NULL,
                content_uri TEXT NOT NULL,
                media_type TEXT NOT NULL,
                captured_at INTEGER,
                added_at INTEGER NOT NULL,
                modified_generation INTEGER,
                mime_type TEXT,
                width INTEGER,
                height INTEGER,
                duration_ms INTEGER,
                relative_path TEXT,
                display_name TEXT,
                volume_name TEXT NOT NULL,
                seen_token TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX media_items_time_idx ON media_items(captured_at DESC, added_at DESC)")
        db.execSQL("CREATE INDEX media_items_volume_idx ON media_items(volume_name)")
        db.execSQL(
            """
            CREATE TABLE media_checkpoints (
                volume_name TEXT PRIMARY KEY,
                media_version TEXT,
                generation INTEGER,
                access_level TEXT NOT NULL,
                last_reconcile_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("No media DB migration exists from $oldVersion to $newVersion")
    }

    override suspend fun get(id: MediaId): MediaItem? = readableDatabase.rawQuery(
        "SELECT * FROM media_items WHERE local_id=?",
        arrayOf(id.value),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toMediaItem() else null }

    override suspend fun get(ids: Collection<MediaId>): List<MediaItem> {
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        return readableDatabase.rawQuery(
            "SELECT * FROM media_items WHERE local_id IN ($placeholders)",
            ids.map { it.value }.toTypedArray(),
        ).use { cursor -> cursor.toMediaItems() }
    }

    override suspend fun list(limit: Int, offset: Int): List<MediaItem> = readableDatabase.rawQuery(
        "SELECT * FROM media_items ORDER BY COALESCE(captured_at, added_at) DESC,media_store_id DESC,local_id LIMIT ? OFFSET ?",
        arrayOf(limit.coerceIn(1, 10_000).toString(), offset.coerceAtLeast(0).toString()),
    ).use { cursor -> cursor.toMediaItems() }

    override suspend fun upsert(items: Collection<MediaItem>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            items.forEach { item -> db.insertItem(item, volumeNameFromId(item.id), null) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun checkpoint(volumeName: String): MediaCheckpoint? = readableDatabase.rawQuery(
        "SELECT media_version, generation, access_level, last_reconcile_ms FROM media_checkpoints WHERE volume_name=?",
        arrayOf(volumeName),
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        MediaCheckpoint(
            version = cursor.getString(0),
            generation = if (cursor.isNull(1)) null else cursor.getLong(1),
            accessLevel = runCatching { MediaAccessLevel.valueOf(cursor.getString(2)) }.getOrDefault(MediaAccessLevel.DENIED),
            lastReconcileAtEpochMs = cursor.getLong(3),
        )
    }

    fun replaceVolumeSnapshot(
        volumeName: String,
        rows: List<ScannedMedia>,
        seenToken: String,
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            rows.forEach { row -> db.insertItem(row.item, volumeName, seenToken) }
            db.delete("media_items", "volume_name=? AND (seen_token IS NULL OR seen_token<>?)", arrayOf(volumeName, seenToken))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun pruneUnavailable(volume: String, visible: Set<Long>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val removed = ArrayList<String>()
            db.rawQuery("SELECT local_id,media_store_id FROM media_items WHERE volume_name=?", arrayOf(volume)).use { cursor ->
                while (cursor.moveToNext()) if (cursor.getLong(1) !in visible) removed.add(cursor.getString(0))
            }
            removed.forEach { db.delete("media_items", "local_id=?", arrayOf(it)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        // This prunes only derived index rows, never phone originals or server archives.
    }

    fun upsertScanned(rows: List<ScannedMedia>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            rows.forEach { row -> db.insertItem(row.item, row.volumeName, null) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun saveCheckpoint(volumeName: String, checkpoint: MediaCheckpoint) {
        val values = ContentValues().apply {
            put("volume_name", volumeName)
            put("media_version", checkpoint.version)
            if (checkpoint.generation == null) putNull("generation") else put("generation", checkpoint.generation)
            put("access_level", checkpoint.accessLevel.name)
            put("last_reconcile_ms", checkpoint.lastReconcileAtEpochMs)
        }
        writableDatabase.insertWithOnConflict("media_checkpoints", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun SQLiteDatabase.insertItem(item: MediaItem, volumeName: String, seenToken: String?) {
        val values = ContentValues().apply {
            put("local_id", item.id.value)
            put("media_store_id", item.mediaStoreId)
            put("content_uri", item.contentUri)
            put("media_type", item.mediaType.name)
            item.capturedAtEpochMs?.let { put("captured_at", it) } ?: putNull("captured_at")
            put("added_at", item.addedAtEpochMs)
            item.modifiedGeneration?.let { put("modified_generation", it) } ?: putNull("modified_generation")
            put("mime_type", item.mimeType)
            item.width?.let { put("width", it) } ?: putNull("width")
            item.height?.let { put("height", it) } ?: putNull("height")
            item.durationMs?.let { put("duration_ms", it) } ?: putNull("duration_ms")
            put("relative_path", item.relativePath)
            put("display_name", item.displayName)
            put("volume_name", volumeName)
            if (seenToken == null) putNull("seen_token") else put("seen_token", seenToken)
        }
        insertWithOnConflict("media_items", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun Cursor.toMediaItems(): List<MediaItem> = buildList {
        while (moveToNext()) add(toMediaItem())
    }

    private fun Cursor.toMediaItem(): MediaItem = MediaItem(
        id = MediaId(getString(getColumnIndexOrThrow("local_id"))),
        mediaStoreId = getLong(getColumnIndexOrThrow("media_store_id")),
        contentUri = getString(getColumnIndexOrThrow("content_uri")),
        mediaType = runCatching { MediaType.valueOf(getString(getColumnIndexOrThrow("media_type"))) }.getOrDefault(MediaType.UNKNOWN),
        capturedAtEpochMs = nullableLong("captured_at"),
        addedAtEpochMs = getLong(getColumnIndexOrThrow("added_at")),
        modifiedGeneration = nullableLong("modified_generation"),
        mimeType = nullableString("mime_type"),
        width = nullableInt("width"),
        height = nullableInt("height"),
        durationMs = nullableLong("duration_ms"),
        relativePath = nullableString("relative_path"),
        displayName = nullableString("display_name"),
    )

    private fun Cursor.nullableLong(column: String): Long? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getLong(index)
    }

    private fun Cursor.nullableInt(column: String): Int? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getInt(index)
    }

    private fun Cursor.nullableString(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    private fun volumeNameFromId(id: MediaId): String = id.value.substringBefore(':', MediaStoreVolumeFallback)

    private companion object {
        const val DB_NAME = "signal-media.db"
        const val DB_VERSION = 1
        const val MediaStoreVolumeFallback = "external"
    }
}
