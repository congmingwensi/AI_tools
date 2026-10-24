package com.nahida.gptprobe

import android.content.Context

object AppSettings {
    private const val PREFS_NAME = "reader_preferences"
    private const val KEY_ENDPOINT = "tts_endpoint"
    private const val KEY_API_TOKEN = "tts_api_token"
    private const val KEY_AUTO_READ = "auto_read_enabled"
    private const val KEY_STATUS = "runtime_status"
    private const val KEY_LATEST_TEXT = "latest_assistant_text"
    private const val KEY_SPOKEN_HASHES = "spoken_hashes"
    private const val MAX_SPOKEN_HASHES = 100

    const val DEFAULT_ENDPOINT = ""

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun endpoint(context: Context): String =
        prefs(context).getString(KEY_ENDPOINT, DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT

    fun setEndpoint(context: Context, value: String) {
        prefs(context).edit().putString(KEY_ENDPOINT, value.trim()).apply()
    }

    fun apiToken(context: Context): String {
        val stored = prefs(context).getString(KEY_API_TOKEN, null)?.trim().orEmpty()
        if (stored.isEmpty()) {
            setApiToken(context, BuildSecrets.DEFAULT_API_TOKEN)
            return BuildSecrets.DEFAULT_API_TOKEN
        }
        return stored
    }

    fun setApiToken(context: Context, value: String) {
        prefs(context).edit().putString(KEY_API_TOKEN, value.trim()).apply()
    }

    fun isAutoReadEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_READ, true)

    fun setAutoReadEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_READ, enabled).apply()
    }

    fun status(context: Context): String =
        prefs(context).getString(KEY_STATUS, "等待无障碍服务启动") ?: ""

    fun setStatus(context: Context, value: String) {
        prefs(context).edit().putString(KEY_STATUS, value).apply()
    }

    fun latestText(context: Context): String =
        prefs(context).getString(KEY_LATEST_TEXT, "") ?: ""

    fun setLatestText(context: Context, value: String) {
        prefs(context).edit().putString(KEY_LATEST_TEXT, value).apply()
    }

    fun wasSpoken(context: Context, hash: String): Boolean =
        prefs(context).getStringSet(KEY_SPOKEN_HASHES, emptySet())?.contains(hash) == true

    fun markSpoken(context: Context, hash: String) {
        val values = LinkedHashSet(
            prefs(context).getStringSet(KEY_SPOKEN_HASHES, emptySet()) ?: emptySet()
        )
        values.remove(hash)
        values.add(hash)
        while (values.size > MAX_SPOKEN_HASHES) {
            val iterator = values.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
        prefs(context).edit().putStringSet(KEY_SPOKEN_HASHES, values).apply()
    }

    fun clearSpokenHistory(context: Context) {
        prefs(context).edit().remove(KEY_SPOKEN_HASHES).apply()
    }
}
