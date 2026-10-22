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
) {
    val isVideo: Boolean get() = kind == "video"
    companion object {
        fun parse(j: JSONObject) = RemotePhoto(
            id = j.getString("media_id"),
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
        )
    }
}
internal data class LocalTransfer(val mediaId: String, val verified: Boolean, val failed: Boolean)

internal class GallerySyncStore(context: Context) : SQLiteOpenHelper(context, "signal-gallery-sync.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE local_sync (local_id TEXT PRIMARY KEY, media_id TEXT NOT NULL, verified INTEGER NOT NULL DEFAULT 0, failed INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE remote_catalog (media_id TEXT PRIMARY KEY, json TEXT NOT NULL, revision INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE progress (key TEXT PRIMARY KEY, value INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("No upgrade path defined")
    }
    fun local(localId: String): LocalTransfer? =
        readableDatabase.rawQuery("SELECT media_id,verified,failed FROM local_sync WHERE local_id=?", arrayOf(localId)).use {
            if (!it.moveToFirst()) null else LocalTransfer(it.getString(0), it.getInt(1) == 1, it.getInt(2) == 1)
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
                db.execSQL("INSERT OR REPLACE INTO remote_catalog(media_id,json,revision) VALUES(?,?,?)",
                    arrayOf(item.id, item.toJson().toString(), item.revision))
                if (item.originalReady) {
                    db.execSQL("UPDATE local_sync SET verified=1,failed=0 WHERE media_id=?", arrayOf(item.id))
                }
            }
            db.execSQL("INSERT OR REPLACE INTO progress(key,value) VALUES('catalog',?)", arrayOf(cursor))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun remote(deviceId: String): List<RemotePhoto> {
        val result = ArrayList<RemotePhoto>()
        readableDatabase.rawQuery("SELECT json FROM remote_catalog ORDER BY revision DESC", null).use { c ->
            while (c.moveToNext()) {
                runCatching { RemotePhoto.parse(JSONObject(c.getString(0))) }.getOrNull()?.let {
                    if (it.deviceId != deviceId) result.add(it)
                }
            }
        }
        return result
    }
    fun localSnapshot(): Map<String, LocalTransfer> {
        val result = LinkedHashMap<String, LocalTransfer>()
        readableDatabase.rawQuery("SELECT local_id,media_id,verified,failed FROM local_sync", null).use { c ->
            while (c.moveToNext()) result[c.getString(0)] =
                LocalTransfer(c.getString(1), c.getInt(2) == 1, c.getInt(3) == 1)
        }
        return result
    }
}

private fun RemotePhoto.toJson() = JSONObject().apply {
    put("media_id", id)
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
}
