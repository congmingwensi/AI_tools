package com.nahida.gptprobe

import android.content.Context
import java.io.File

object ProbeStorage {
    private const val PREFS_NAME = "probe_preferences"
    private const val KEY_RECORDING = "recording_enabled"
    private const val LOG_DIRECTORY = "accessibility_probe"
    private const val LOG_FILE = "events.jsonl"

    fun isRecording(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_RECORDING, false)
    }

    fun setRecording(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_RECORDING, enabled)
            .apply()
    }

    private fun logFile(context: Context): File {
        val directory = File(context.filesDir, LOG_DIRECTORY)
        if (!directory.exists()) {
            directory.mkdirs()
        }
        return File(directory, LOG_FILE)
    }

    @Synchronized
    fun append(context: Context, json: String) {
        logFile(context).appendText(json + "\n", Charsets.UTF_8)
    }

    @Synchronized
    fun clear(context: Context) {
        val file = logFile(context)
        if (file.exists()) {
            file.writeText("", Charsets.UTF_8)
        }
    }

    @Synchronized
    fun recordCount(context: Context): Int {
        val file = logFile(context)
        if (!file.exists() || file.length() == 0L) return 0
        return file.useLines(Charsets.UTF_8) { lines -> lines.count { it.isNotBlank() } }
    }

    @Synchronized
    fun exportJson(context: Context): String {
        val file = logFile(context)
        if (!file.exists() || file.length() == 0L) {
            return "[]"
        }
        val records = file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        return records.joinToString(prefix = "[\n", postfix = "\n]", separator = ",\n")
    }
}

