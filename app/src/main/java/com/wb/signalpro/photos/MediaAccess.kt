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

        return when {
            Build.VERSION.SDK_INT >= 34 -> {
                val images = granted(Manifest.permission.READ_MEDIA_IMAGES)
                val videos = granted(Manifest.permission.READ_MEDIA_VIDEO)
                val selected = granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                when {
                    images && videos -> MediaAccessLevel.FULL
                    images || videos || selected -> MediaAccessLevel.PARTIAL
                    else -> MediaAccessLevel.DENIED
                }
            }
            Build.VERSION.SDK_INT >= 33 -> {
                val images = granted(Manifest.permission.READ_MEDIA_IMAGES)
                val videos = granted(Manifest.permission.READ_MEDIA_VIDEO)
                when {
                    images && videos -> MediaAccessLevel.FULL
                    images || videos -> MediaAccessLevel.PARTIAL
                    else -> MediaAccessLevel.DENIED
                }
            }
            else -> if (granted(Manifest.permission.READ_EXTERNAL_STORAGE)) {
                MediaAccessLevel.FULL
            } else {
                MediaAccessLevel.DENIED
            }
        }
    }
}
