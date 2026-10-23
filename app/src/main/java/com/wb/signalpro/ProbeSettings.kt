package com.wb.signalpro

import android.content.Context

object ProbeSettings {
    private const val PREFS = "signal_probe"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_LAST_EOS = "last_eos"

    fun getBaseUrl(context: Context): String = prefs(context).getString(KEY_BASE_URL, "")?.trim()?.trimEnd('/').orEmpty()

    fun setBaseUrl(context: Context, value: String) {
        prefs(context).edit().putString(KEY_BASE_URL, value.trim().trimEnd('/')).apply()
    }

    fun clearBaseUrl(context: Context) {
        prefs(context).edit().remove(KEY_BASE_URL).apply()
    }

    fun getLastEos(context: Context): String = prefs(context).getString(KEY_LAST_EOS, "")?.trim().orEmpty()

    fun setLastEos(context: Context, value: String) {
        if (value.isNotBlank()) prefs(context).edit().putString(KEY_LAST_EOS, value.trim()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
