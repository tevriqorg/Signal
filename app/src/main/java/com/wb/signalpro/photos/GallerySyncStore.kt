package com.wb.signalpro.photos

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

internal data class RemotePhoto(
    val id: String,
    val deviceId: String,
    val deviceName: String,
    val kind: String,
    val mime: String,
    val capturedAt: Long,
    val fileName: String,
    val size: Long?,
    val sha256: String?,
    val thumbReady: Boolean,
    val originalReady: Boolean,
    val revision: Long,
    val deleted: Boolean = false,
    val matchReasons: String = "",
    val centerUrl: String = "",
) {
    val isVideo: Boolean get() = kind == "video"
    companion object {
        fun parse(j: JSONObject) = RemotePhoto(
            id = j.getString("media_id"),
            centerUrl = j.optString("_center_url", ""),
            deviceId = j.getString("origin_device_id"),
            deviceName = j.optString("device_name", "未知设备"),
            kind = j.optString("media_type", "photo"),
            mime = j.optString("mime_type", "image/jpeg"),
            capturedAt = j.optLong("captured_at"),
            fileName = j.optString("filename", ""),
            size = if (j.isNull("size")) null else j.optLong("size"),
            sha256 = if (j.isNull("sha256")) null else j.optString("sha256"),
            thumbReady = j.optBoolean("thumbnail_ready"),
            originalReady = j.optBoolean("original_ready"),
            revision = j.optLong("revision"),
            deleted = j.optBoolean("deleted"),
            matchReasons = j.optJSONArray("match_reasons")?.let { reasons ->
                (0 until reasons.length()).map { index ->
                    val reason = reasons.getJSONObject(index)
                    if (reason.optString("kind") == "location") "拍摄位置附近：约 " + reason.optInt("distance_m") + " 米"
                    else if (reason.optString("kind") == "time") "拍摄日期：" + reason.optString("date")
                    else if (reason.optString("kind") == "visual_similarity") "画面内容相近"
                    else "文字或元数据：" + reason.optString("excerpt").take(100)
                }.joinToString("；")
            } ?: "",
        )
    }
}
internal data class LocalTransfer(val mediaId: String, val verified: Boolean, val failed: Boolean, val sourceVersion: String? = null)

internal class GallerySyncStore(context: Context, databaseName: String = "signal-gallery-sync.db") : SQLiteOpenHelper(context, databaseName, null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE local_sync (local_id TEXT PRIMARY KEY, media_id TEXT NOT NULL, verified INTEGER NOT NULL DEFAULT 0, failed INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE remote_catalog (media_id TEXT PRIMARY KEY, json TEXT NOT NULL, revision INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE source_versions (local_id TEXT PRIMARY KEY, version TEXT NOT NULL)")
        db.execSQL("CREATE TABLE progress (key TEXT PRIMARY KEY, value INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE source_versions (local_id TEXT PRIMARY KEY, version TEXT NOT NULL)")
            db.execSQL("UPDATE local_sync SET verified=0")
        }
    }
    /** Clear derived protocol state only. Phone and center originals are untouched. */
    fun resetCenter() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (table in listOf("local_sync", "remote_catalog", "source_versions", "progress")) db.delete(table, null, null)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun thumbnailReady(mediaId: String): Boolean = readableDatabase.rawQuery(
        "SELECT json FROM remote_catalog WHERE media_id=?", arrayOf(mediaId)).use {
        it.moveToFirst() && JSONObject(it.getString(0)).optBoolean("thumbnail_ready")
    }
    fun sourceVersions(): Map<String, String> = buildMap {
        readableDatabase.rawQuery("SELECT local_id,version FROM source_versions", null).use { cursor ->
            while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1))
        }
    }
    fun sourceVersion(localId: String): String? = readableDatabase.rawQuery(
        "SELECT version FROM source_versions WHERE local_id=?", arrayOf(localId)).use {
        if (it.moveToFirst()) it.getString(0) else null
    }
    fun saveSourceVersion(localId: String, version: String) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO source_versions VALUES(?,?)", arrayOf(localId, version))
    }
    fun local(localId: String): LocalTransfer? =
        readableDatabase.rawQuery("SELECT l.media_id,l.verified,l.failed,v.version FROM local_sync l LEFT JOIN source_versions v ON v.local_id=l.local_id WHERE l.local_id=?", arrayOf(localId)).use {
            if (!it.moveToFirst()) null else LocalTransfer(it.getString(0), it.getInt(1) == 1, it.getInt(2) == 1, if (it.isNull(3)) null else it.getString(3))
        }
    fun saveLocal(localId: String, mediaId: String, verified: Boolean, failed: Boolean = false) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO local_sync(local_id,media_id,verified,failed) VALUES(?,?,?,?)",
            arrayOf(localId, mediaId, if (verified) 1 else 0, if (failed) 1 else 0))
    }
    fun cursor(): Long = readableDatabase.rawQuery("SELECT value FROM progress WHERE key='catalog'", null).use {
        if (it.moveToFirst()) it.getLong(0) else 0L
    }
    fun ingest(items: List<RemotePhoto>, cursor: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            items.forEach { item ->
                val revision = db.rawQuery("SELECT revision FROM remote_catalog WHERE media_id=?", arrayOf(item.id)).use {
                    if (it.moveToFirst()) it.getLong(0) else -1L
                }
                if (item.revision < revision) return@forEach
                db.execSQL("INSERT OR REPLACE INTO remote_catalog(media_id,json,revision) VALUES(?,?,?)",
                    arrayOf(item.id, item.toJson().toString(), item.revision))
                if (!item.originalReady) db.execSQL("UPDATE local_sync SET verified=0 WHERE media_id=?", arrayOf(item.id))
            }
            db.execSQL("INSERT OR REPLACE INTO progress(key,value) VALUES('catalog',?)", arrayOf(maxOf(this.cursor(), cursor)))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun allCatalog(): List<RemotePhoto> {
        val result = ArrayList<RemotePhoto>()
        readableDatabase.rawQuery("SELECT json FROM remote_catalog ORDER BY revision DESC", null).use { c ->
            while (c.moveToNext()) {
                runCatching { RemotePhoto.parse(JSONObject(c.getString(0))) }.getOrNull()?.let {
                    if (!it.deleted) result.add(it)
                }
            }
        }
        return result
    }
    fun remote(deviceId: String) = allCatalog().filter { it.deviceId != deviceId }
    fun localSnapshot(): Map<String, LocalTransfer> {
        val result = LinkedHashMap<String, LocalTransfer>()
        readableDatabase.rawQuery("SELECT l.local_id,l.media_id,l.verified,l.failed,v.version FROM local_sync l LEFT JOIN source_versions v ON v.local_id=l.local_id", null).use { c ->
            while (c.moveToNext()) result[c.getString(0)] =
                LocalTransfer(c.getString(1), c.getInt(2) == 1, c.getInt(3) == 1, if (c.isNull(4)) null else c.getString(4))
        }
        return result
    }
}

private fun RemotePhoto.toJson() = JSONObject().apply {
    put("media_id", id).put("_center_url", centerUrl)
    put("origin_device_id", deviceId)
    put("device_name", deviceName)
    put("media_type", kind)
    put("mime_type", mime)
    put("captured_at", capturedAt)
    put("filename", fileName)
    put("size", size)
    put("sha256", sha256)
    put("thumbnail_ready", thumbReady)
    put("original_ready", originalReady)
    put("revision", revision)
    put("deleted", deleted)
}
