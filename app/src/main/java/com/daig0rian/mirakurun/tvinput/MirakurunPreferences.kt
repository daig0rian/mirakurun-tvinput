package com.daig0rian.mirakurun.tvinput

import android.content.Context

internal object MirakurunPreferences {

    private const val PREFS_NAME = "mirakurun_settings"
    private const val KEY_URL = "mirakurun_url"
    private const val DEFAULT_URL = "http://192.168.0.0:40772"

    fun getUrl(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL

    fun isUrlConfigured(context: Context): Boolean =
        getUrl(context) != DEFAULT_URL

    fun setUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_URL, url.trimEnd('/'))
            .apply()
    }
}
