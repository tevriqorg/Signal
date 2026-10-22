package com.wb.signalpro

import android.content.Context

object WoPanSettings {
    private const val PREFS = "signal_wopan_probe"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_LAST_HOST = "last_host"
    const val DEFAULT_URL = ""

    fun getBaseUrl(context: Context): String =
        prefs(context).getString(KEY_BASE_URL, DEFAULT_URL)?.trim()?.trimEnd('/').orEmpty().ifBlank { DEFAULT_URL }

    fun setBaseUrl(context: Context, value: String) {
        prefs(context).edit().putString(KEY_BASE_URL, value.trim().trimEnd('/')).apply()
    }

    fun clearBaseUrl(context: Context) {
        prefs(context).edit().remove(KEY_BASE_URL).apply()
    }

    fun setLastHost(context: Context, value: String) {
        if (value.isNotBlank()) prefs(context).edit().putString(KEY_LAST_HOST, value.trim()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
