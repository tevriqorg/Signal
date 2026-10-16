package com.wb.signalpro.photos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object MediaPermissionPolicy {
    fun requestedPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun current(context: Context): MediaAccessLevel {
        fun granted(permission: String) =
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

        return resolve(
            sdkInt = Build.VERSION.SDK_INT,
            images = Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_IMAGES),
            videos = Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_VIDEO),
            selected = Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
            legacy = Build.VERSION.SDK_INT < 33 && granted(Manifest.permission.READ_EXTERNAL_STORAGE),
        )
    }

    fun resolve(
        sdkInt: Int,
        images: Boolean,
        videos: Boolean,
        selected: Boolean,
        legacy: Boolean,
    ): MediaAccessLevel = when {
        sdkInt >= 34 -> when {
            images && videos -> MediaAccessLevel.FULL
            images || videos || selected -> MediaAccessLevel.PARTIAL
            else -> MediaAccessLevel.DENIED
        }
        sdkInt >= 33 -> when {
            images && videos -> MediaAccessLevel.FULL
            images || videos -> MediaAccessLevel.PARTIAL
            else -> MediaAccessLevel.DENIED
        }
        legacy -> MediaAccessLevel.FULL
        else -> MediaAccessLevel.DENIED
    }
}
