package com.nahida.gptprobe

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView

class FloatingStatusOverlay(
    private val service: AccessibilityService,
    private val onStatusTap: (TtsClient.PlaybackState) -> Unit
) {
    private val windowManager =
        service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val displayMetrics = DisplayMetrics().also {
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(it)
    }
    private val width = dp(96)
    private val height = dp(42)
    private var added = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false
    private var currentState = TtsClient.PlaybackState.WAITING

    private val params = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = (displayMetrics.widthPixels - width - dp(10)).coerceAtLeast(0)
        y = displayMetrics.heightPixels / 3
    }

    private val view = TextView(service).apply {
        gravity = Gravity.CENTER
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        elevation = dp(6).toFloat()
        setPadding(dp(10), 0, dp(10), 0)
        setOnTouchListener { _, event -> handleTouch(event) }
    }

    fun show() {
        if (added) return
        setState(TtsClient.PlaybackState.WAITING)
        try {
            windowManager.addView(view, params)
            added = true
        } catch (_: Exception) {
        }
    }

    fun setState(state: TtsClient.PlaybackState) {
        currentState = state
        val style = when (state) {
            TtsClient.PlaybackState.WAITING -> StatusStyle(
                "● 等待", "正在等待", Color.rgb(42, 67, 58), Color.rgb(121, 158, 143)
            )
            TtsClient.PlaybackState.CONNECTING -> StatusStyle(
                "↻ 连接", "正在连接电脑并生成语音",
                Color.rgb(183, 112, 20), Color.rgb(255, 214, 128)
            )
            TtsClient.PlaybackState.READING -> StatusStyle(
                "♪ 朗读", "正在朗读", Color.rgb(16, 163, 127), Color.WHITE
            )
            TtsClient.PlaybackState.PAUSED -> StatusStyle(
                "Ⅱ 暂停", "朗读已暂停，点击继续",
                Color.rgb(88, 93, 112), Color.rgb(190, 196, 218)
            )
        }
        view.text = style.label
        view.contentDescription = style.description
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = height / 2f
            setColor(style.fillColor)
            setStroke(dp(1), style.borderColor)
        }
    }

    private data class StatusStyle(
        val label: String,
        val description: String,
        val fillColor: Int,
        val borderColor: Int
    )

    fun hide() {
        if (!added) return
        try { windowManager.removeView(view) } catch (_: Exception) {}
        added = false
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startX = params.x
                startY = params.y
                moved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaX = (event.rawX - downRawX).toInt()
                val deltaY = (event.rawY - downRawY).toInt()
                if (kotlin.math.abs(deltaX) > dp(4) || kotlin.math.abs(deltaY) > dp(4)) {
                    moved = true
                }
                params.x = (startX + deltaX).coerceIn(0, displayMetrics.widthPixels - width)
                params.y = (startY + deltaY).coerceIn(0, displayMetrics.heightPixels - height)
                if (added) {
                    try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {}
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) {
                    if (currentState == TtsClient.PlaybackState.CONNECTING) {
                        openApp()
                    } else {
                        onStatusTap(currentState)
                    }
                }
                return true
            }
        }
        return false
    }

    private fun openApp() {
        service.startActivity(
            Intent(service, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
    }

    private fun dp(value: Int): Int =
        (value * service.resources.displayMetrics.density + 0.5f).toInt()
}
