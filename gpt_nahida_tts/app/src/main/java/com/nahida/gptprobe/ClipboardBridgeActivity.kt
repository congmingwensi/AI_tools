package com.nahida.gptprobe

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * Android 10+ only permits the focused app (or the current IME) to read the clipboard.
 * This transparent activity takes focus briefly, reads once, and immediately returns
 * to ChatGPT without adding a task to Recents.
 */
class ClipboardBridgeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var consumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(Color.TRANSPARENT)
        window.setDimAmount(0f)
        overridePendingTransition(0, 0)
        handler.postDelayed({ readAndFinish() }, FALLBACK_DELAY_MS)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) handler.postDelayed({ readAndFinish() }, FOCUS_SETTLE_DELAY_MS)
    }

    private fun readAndFinish() {
        if (consumed) return
        consumed = true
        val text = try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip == null || clip.itemCount == 0) null
            else clip.getItemAt(0).coerceToText(this)?.toString()
        } catch (_: Exception) {
            null
        }
        val callback = pendingCallback
        pendingCallback = null
        callback?.invoke(text)
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        @Volatile private var pendingCallback: ((String?) -> Unit)? = null

        fun readOnce(context: Context, callback: (String?) -> Unit) {
            pendingCallback = callback
            val intent = Intent(context, ClipboardBridgeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            context.startActivity(intent)
        }

        private const val FOCUS_SETTLE_DELAY_MS = 80L
        private const val FALLBACK_DELAY_MS = 650L
    }
}
