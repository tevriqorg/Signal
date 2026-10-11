package com.wb.signalpro

import android.content.Context

object ProbeSettings {
    private const val PREFS = "signal_probe"
    private const val KEY_BASE_URL = "base_url"

    fun getBaseUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BASE_URL, "")
            ?.trim()
            ?.trimEnd('/')
            .orEmpty()

    fun setBaseUrl(context: Context, value: String) {
        val normalized = value.trim().trimEnd('/')
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BASE_URL, normalized)
            .apply()
    }
}
