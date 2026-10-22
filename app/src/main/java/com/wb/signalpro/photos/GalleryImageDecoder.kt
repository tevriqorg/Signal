package com.wb.signalpro.photos

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** Decode for display within a fixed pixel budget; retain the untouched complete cached original.
 * Android ImageDecoder also applies EXIF orientation and supports platform HEIF codecs.
 */
internal fun decodeGalleryImage(file: File, maximumEdge: Int): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
        val edge = max(info.size.width, info.size.height)
        val scale = minOf(1f, maximumEdge.coerceAtLeast(1).toFloat() / edge.coerceAtLeast(1))
        decoder.setTargetSize(max(1, (info.size.width * scale).roundToInt()), max(1, (info.size.height * scale).roundToInt()))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
