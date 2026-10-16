package com.wb.signalpro.photos

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.wb.signalpro.data.model.MediaId
import com.wb.signalpro.data.model.MediaItem
import com.wb.signalpro.data.model.MediaType
import java.util.Locale

internal data class ScannedMedia(
    val volumeName: String,
    val item: MediaItem,
)

internal class MediaStoreSource(private val context: Context) {
    private val resolver = context.contentResolver

    fun externalVolumes(): Set<String> = if (Build.VERSION.SDK_INT >= 30) {
        MediaStore.getExternalVolumeNames(context).ifEmpty { setOf(MediaStore.VOLUME_EXTERNAL_PRIMARY) }
    } else {
        setOf(MediaStore.VOLUME_EXTERNAL)
    }

    fun snapshot(volumeName: String): MediaStoreSnapshot = if (Build.VERSION.SDK_INT >= 30) {
        MediaStoreSnapshot(
            version = MediaStore.getVersion(context, volumeName),
            generation = MediaStore.getGeneration(context, volumeName),
        )
    } else {
        MediaStoreSnapshot(
            version = MediaStore.getVersion(context),
            generation = null,
        )
    }

    fun query(volumeName: String, afterGeneration: Long?): List<ScannedMedia> {
        val uri = MediaStore.Files.getContentUri(volumeName)
        val generationColumn = if (Build.VERSION.SDK_INT >= 30) {
            MediaStore.MediaColumns.GENERATION_MODIFIED
        } else {
            MediaStore.MediaColumns.DATE_MODIFIED
        }
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.MIME_TYPE,
            DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            generationColumn,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.Video.VideoColumns.DURATION,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DISPLAY_NAME,
        )
        val baseSelection = "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=?)"
        val incremental = Build.VERSION.SDK_INT >= 30 && afterGeneration != null
        val selection = if (incremental) {
            "$baseSelection AND ${MediaStore.MediaColumns.GENERATION_MODIFIED}>?"
        } else {
            baseSelection
        }
        val selectionArgs = buildList {
            add(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString())
            add(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString())
            if (incremental) add(afterGeneration.toString())
        }.toTypedArray()
        val orderBy = "$generationColumn ASC"

        val result = mutableListOf<ScannedMedia>()
        resolver.query(uri, projection, selection, selectionArgs, orderBy)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val mediaTypeIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val takenIndex = cursor.getColumnIndexOrThrow(DATE_TAKEN)
            val addedIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val generationIndex = cursor.getColumnIndexOrThrow(generationColumn)
            val widthIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
            val heightIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
            val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.VideoColumns.DURATION)
            val pathIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val mediaKind = cursor.getInt(mediaTypeIndex)
                val relativePath = cursor.getString(pathIndex)
                val displayName = cursor.getString(nameIndex)
                val type = when (mediaKind) {
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> {
                        if (looksLikeScreenshot(relativePath, displayName)) MediaType.SCREENSHOT else MediaType.PHOTO
                    }
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> MediaType.VIDEO
                    else -> MediaType.UNKNOWN
                }
                val addedSeconds = cursor.getLong(addedIndex)
                val captured = cursor.getLong(takenIndex).takeIf { it > 0L }
                    ?: addedSeconds.takeIf { it > 0L }?.times(1_000L)
                val contentUri = ContentUris.withAppendedId(uri, id).toString()
                result += ScannedMedia(
                    volumeName = volumeName,
                    item = MediaItem(
                        id = MediaId("$volumeName:$id"),
                        mediaStoreId = id,
                        contentUri = contentUri,
                        mediaType = type,
                        capturedAtEpochMs = captured,
                        addedAtEpochMs = addedSeconds * 1_000L,
                        modifiedGeneration = cursor.getLong(generationIndex).takeIf { !cursor.isNull(generationIndex) },
                        mimeType = cursor.getString(mimeIndex),
                        width = cursor.getInt(widthIndex).takeIf { !cursor.isNull(widthIndex) },
                        height = cursor.getInt(heightIndex).takeIf { !cursor.isNull(heightIndex) },
                        durationMs = cursor.getLong(durationIndex).takeIf { !cursor.isNull(durationIndex) && it > 0L },
                        relativePath = relativePath,
                        displayName = displayName,
                    ),
                )
            }
        }
        return result
    }

    private fun looksLikeScreenshot(path: String?, name: String?): Boolean {
        val haystack = listOfNotNull(path, name).joinToString("/").lowercase(Locale.ROOT)
        return listOf("screenshot", "screen_shot", "screen-shot", "截屏", "截图").any(haystack::contains)
    }

    private companion object {
        const val DATE_TAKEN = "datetaken"
    }
}
