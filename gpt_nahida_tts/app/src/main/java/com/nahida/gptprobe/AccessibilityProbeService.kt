package com.nahida.gptprobe

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.text.SimpleDateFormat
import java.security.MessageDigest
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class AccessibilityProbeService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor()
    private var lastSnapshotElapsed = 0L
    private var pendingMetadata: EventMetadata? = null
    private var pendingScheduled = false
    private var skippedEvents = 0
    private var lastCandidateText: String? = null
    private var stablePolls = 0
    private var inFlightHash: String? = null
    private var failedHash: String? = null
    private var failedAtElapsed = 0L
    private var clipboardCopyInProgress = false
    private var clipboardRetryCount = 0
    private var lastResolvedFullHash: String? = null
    private var lastResolvedFullText: String? = null
    private var statusOverlay: FloatingStatusOverlay? = null

    private val autoReadPoll = object : Runnable {
        override fun run() {
            try {
                pollLatestAssistantMessage()
            } catch (error: Exception) {
                AppSettings.setStatus(
                    applicationContext,
                    "读取 ChatGPT 失败：${error.message ?: error.javaClass.simpleName}"
                )
            } finally {
                handler.postDelayed(this, AUTO_READ_POLL_MS)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo?.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        AppSettings.setStatus(applicationContext, "无障碍服务已启动，等待打开 ChatGPT")
        statusOverlay?.hide()
        statusOverlay = FloatingStatusOverlay(this, ::handleStatusTap).also { it.show() }
        TtsClient.setPlaybackStateListener { state ->
            statusOverlay?.setState(state)
        }
        handler.removeCallbacks(autoReadPoll)
        handler.post(autoReadPoll)
    }

    private fun handleStatusTap(state: TtsClient.PlaybackState) {
        when (state) {
            TtsClient.PlaybackState.WAITING -> repeatLatestAnswer()
            TtsClient.PlaybackState.READING,
            TtsClient.PlaybackState.PAUSED -> TtsClient.togglePauseResume(this)
            TtsClient.PlaybackState.CONNECTING -> Unit
        }
    }

    private fun repeatLatestAnswer() {
        val text = AppSettings.latestText(this).trim()
        if (text.isEmpty()) {
            AppSettings.setStatus(this, "还没有可重复朗读的回答")
            return
        }
        if (TtsClient.isBusy()) return
        AppSettings.setStatus(this, "正在重新朗读最近回答")
        Log.i(TAG, "manual replay requested chars=${text.length}")
        TtsClient.speak(
            this,
            text,
            onStarted = {
                Log.i(TAG, "manual replay started")
            },
            onCompleted = {
                Log.i(TAG, "manual replay completed")
            },
            onError = { message ->
                Log.e(TAG, "manual replay failed message=$message")
                AppSettings.setStatus(applicationContext, "重新朗读失败：$message")
            }
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !ProbeStorage.isRecording(this)) return
        if (event.packageName?.toString() != CHATGPT_PACKAGE) return

        val metadata = EventMetadata.from(event)
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastSnapshotElapsed
        if (elapsed >= SNAPSHOT_INTERVAL_MS && !pendingScheduled) {
            capture(metadata)
            return
        }

        pendingMetadata = metadata
        skippedEvents += 1
        if (!pendingScheduled) {
            pendingScheduled = true
            val delay = (SNAPSHOT_INTERVAL_MS - elapsed).coerceAtLeast(1L)
            handler.postDelayed({
                pendingScheduled = false
                val latest = pendingMetadata
                pendingMetadata = null
                if (latest != null && ProbeStorage.isRecording(this)) {
                    capture(latest)
                }
            }, delay)
        }
    }

    private fun capture(metadata: EventMetadata) {
        lastSnapshotElapsed = SystemClock.elapsedRealtime()
        val skipped = skippedEvents
        skippedEvents = 0
        val root = rootInActiveWindow
        val json = buildRecord(metadata, root, skipped)
        try {
            root?.recycle()
        } catch (_: Exception) {
        }
        writer.execute {
            try {
                ProbeStorage.append(applicationContext, json)
            } catch (_: Exception) {
            }
        }
    }

    private fun pollLatestAssistantMessage() {
        if (!AppSettings.isAutoReadEnabled(this)) {
            resetCandidate()
            AppSettings.setStatus(this, "自动朗读已暂停")
            return
        }
        if (TtsClient.isBusy() || clipboardCopyInProgress) return

        val root = rootInActiveWindow
        if (root == null) {
            resetCandidate()
            AppSettings.setStatus(this, "等待取得 ChatGPT 窗口")
            return
        }

        val packageName = try { root.packageName?.toString() } catch (_: Exception) { null }
        if (packageName != CHATGPT_PACKAGE) {
            try { root.recycle() } catch (_: Exception) {}
            resetCandidate()
            AppSettings.setStatus(this, "等待 ChatGPT 位于前台")
            return
        }

        val hasCopy = ChatGptMessageExtractor.hasVisibleAnswerCopy(root, visibleChatBounds(root))
        try { root.recycle() } catch (_: Exception) {}
        if (!hasCopy) {
            AppSettings.setStatus(this, "屏幕内没有复制按钮，3 秒后再检查")
            return
        }

        // Retry the already acquired answer without clicking Copy or taking window focus.
        val cachedText = lastResolvedFullText
        val cachedHash = lastResolvedFullHash
        if (cachedText != null && cachedHash != null && failedHash == cachedHash) {
            speakCachedAnswer(cachedText, cachedHash)
            return
        }

        requestFullAnswerThroughClipboard()
    }

    private fun visibleChatBounds(root: AccessibilityNodeInfo): Rect {
        val bounds = Rect()
        root.getBoundsInScreen(bounds)
        for (window in windows) {
            if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                val keyboard = Rect()
                window.getBoundsInScreen(keyboard)
                if (Rect.intersects(bounds, keyboard)) bounds.bottom = minOf(bounds.bottom, keyboard.top)
            }
        }
        return bounds
    }

    private fun requestFullAnswerThroughClipboard() {
        clipboardRetryCount = 0
        clipboardCopyInProgress = true

        val root = rootInActiveWindow
        val clicked = if (root == null) {
            false
        } else {
            try {
                val packageName = try { root.packageName?.toString() } catch (_: Exception) { null }
                packageName == CHATGPT_PACKAGE &&
                    ChatGptMessageExtractor.clickLatestAnswerCopy(root, visibleChatBounds(root))
            } finally {
                try { root.recycle() } catch (_: Exception) {}
            }
        }

        if (!clicked) {
            clipboardCopyInProgress = false
            resetCandidate()
            AppSettings.setStatus(this, "找到回答，但点击复制按钮失败")
            Log.w(TAG, "answer copy action failed")
            return
        }

        AppSettings.setStatus(this, "正在复制并读取完整回答")
        Log.i(TAG, "answer copy clicked; waiting for clipboard")
        handler.postDelayed(::requestClipboardBridgeRead, CLIPBOARD_READ_DELAY_MS)
    }

    private fun requestClipboardBridgeRead() {
        if (!clipboardCopyInProgress) return
        try {
            ClipboardBridgeActivity.readOnce(this) { copied ->
                handler.post { consumeCopiedAnswer(copied) }
            }
        } catch (error: Exception) {
            Log.w(TAG, "clipboard bridge launch failed", error)
            consumeCopiedAnswer(null)
        }
    }

    private fun consumeCopiedAnswer(copiedFromBridge: String?) {
        if (!clipboardCopyInProgress) return
        val copied = copiedFromBridge?.trim()
        if (!copied.isNullOrEmpty()) {
            clipboardCopyInProgress = false
            handleCopiedAnswer(copied)
            return
        }

        clipboardRetryCount += 1
        if (clipboardRetryCount < CLIPBOARD_READ_ATTEMPTS) {
            handler.postDelayed(::requestClipboardBridgeRead, CLIPBOARD_RETRY_DELAY_MS)
            return
        }

        clipboardCopyInProgress = false
        resetCandidate()
        AppSettings.setStatus(this, "剪贴板为空或系统禁止读取")
        Log.w(TAG, "clipboard answer rejected chars=${copied?.length ?: 0}")
    }

    private fun handleCopiedAnswer(fullAnswer: String) {
        val text = fullAnswer.trim()
        val hash = sha256(text)
        lastResolvedFullHash = hash
        lastResolvedFullText = text
        resetCandidate()
        AppSettings.setLatestText(this, text)
        Log.i(TAG, "clipboard answer accepted fullChars=${fullAnswer.length} ttsChars=${text.length} hash=${hash.take(12)}")

        speakCachedAnswer(text, hash)
    }

    private fun speakCachedAnswer(text: String, hash: String) {
        if (AppSettings.wasSpoken(this, hash)) {
            if (failedHash == hash) failedHash = null
            AppSettings.setStatus(this, "剪贴板已取得完整回答；该回答已经朗读过")
            return
        }
        if (inFlightHash != null) return
        if (failedHash == hash &&
            SystemClock.elapsedRealtime() - failedAtElapsed < FAILED_RETRY_DELAY_MS
        ) return

        inFlightHash = hash
        AppSettings.setStatus(this, "正在使用缓存回答请求 TTS")
        Log.i(TAG, "cached answer TTS requested hash=${hash.take(12)}")
        TtsClient.speak(
            this,
            text,
            onStarted = {
                Log.i(TAG, "clipboard playback acknowledged hash=${hash.take(12)}")
            },
            onCompleted = {
                AppSettings.markSpoken(applicationContext, hash)
                inFlightHash = null
                failedHash = null
                Log.i(TAG, "clipboard answer marked spoken hash=${hash.take(12)}")
            },
            onError = { message ->
                inFlightHash = null
                failedHash = hash
                failedAtElapsed = SystemClock.elapsedRealtime()
                Log.e(TAG, "clipboard TTS failed hash=${hash.take(12)} message=$message")
                AppSettings.setStatus(applicationContext, "TTS 失败，稍后重试：$message")
            }
        )
    }

    private fun pollLatestAssistantMessageLegacy() {
        if (!AppSettings.isAutoReadEnabled(this)) {
            resetCandidate()
            AppSettings.setStatus(this, "自动朗读已暂停")
            return
        }
        if (TtsClient.isBusy()) return

        val root = rootInActiveWindow
        if (root == null) {
            resetCandidate()
            AppSettings.setStatus(this, "未取得当前窗口，等待 ChatGPT")
            return
        }

        val packageName = try { root.packageName?.toString() } catch (_: Exception) { null }
        if (packageName != CHATGPT_PACKAGE) {
            try { root.recycle() } catch (_: Exception) {}
            resetCandidate()
            AppSettings.setStatus(this, "等待 ChatGPT 位于前台")
            return
        }

        val result = try {
            ChatGptMessageExtractor.extract(root)
        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }
        val text = result.text
        if (text.isNullOrBlank()) {
            resetCandidate()
            AppSettings.setStatus(this, result.reason)
            return
        }

        AppSettings.setLatestText(this, text)
        val hash = sha256(text)
        if (AppSettings.wasSpoken(this, hash)) {
            resetCandidate()
            AppSettings.setStatus(this, "最新回答已经朗读过，继续监听")
            return
        }
        if (text == lastCandidateText) {
            stablePolls += 1
        } else {
            lastCandidateText = text
            stablePolls = 1
            Log.i(TAG, "new candidate hash=${hash.take(12)} chars=${text.length}")
            AppSettings.setStatus(this, "已发现完整回答，3 秒后复核")
            return
        }

        if (stablePolls < REQUIRED_STABLE_POLLS) return
        if (inFlightHash != null) {
            AppSettings.setStatus(this, "上一条语音仍在生成")
            return
        }
        if (failedHash == hash &&
            SystemClock.elapsedRealtime() - failedAtElapsed < FAILED_RETRY_DELAY_MS
        ) {
            return
        }

        inFlightHash = hash
        Log.i(TAG, "candidate stable hash=${hash.take(12)} polls=$stablePolls; requesting TTS")
        AppSettings.setStatus(this, "回答已稳定，正在发送到 TTS")
        TtsClient.speak(
            this,
            text,
            onStarted = {
                Log.i(TAG, "playback acknowledged hash=${hash.take(12)}; waiting for completion")
            },
            onCompleted = {
                AppSettings.markSpoken(applicationContext, hash)
                inFlightHash = null
                failedHash = null
                Log.i(TAG, "marked spoken after completion hash=${hash.take(12)}")
            },
            onError = { message ->
                inFlightHash = null
                failedHash = hash
                failedAtElapsed = SystemClock.elapsedRealtime()
                Log.e(TAG, "TTS failed hash=${hash.take(12)} message=$message")
                AppSettings.setStatus(applicationContext, "TTS 失败，6 秒后重试：$message")
            }
        )
    }

    private fun resetCandidate() {
        lastCandidateText = null
        stablePolls = 0
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun buildRecord(
        metadata: EventMetadata,
        root: AccessibilityNodeInfo?,
        skipped: Int
    ): String {
        val output = StringBuilder(64 * 1024)
        output.append('{')
        output.append("\"timestampEpochMs\":").append(metadata.timestampEpochMs).append(',')
        output.append("\"timestamp\":").append(JsonSupport.quote(formatTime(metadata.timestampEpochMs))).append(',')
        output.append("\"eventType\":").append(metadata.eventType).append(',')
        output.append("\"eventTypeName\":").append(JsonSupport.quote(metadata.eventTypeName)).append(',')
        output.append("\"packageName\":").append(JsonSupport.quote(metadata.packageName)).append(',')
        output.append("\"className\":").append(JsonSupport.quote(metadata.className)).append(',')
        output.append("\"contentChangeTypes\":").append(metadata.contentChangeTypes).append(',')
        output.append("\"eventText\":").append(JsonSupport.stringArray(metadata.eventText)).append(',')
        output.append("\"source\":").append(metadata.sourceJson).append(',')
        output.append("\"eventsSkippedSincePreviousSnapshot\":").append(skipped).append(',')
        output.append("\"root\":")
        if (root == null) {
            output.append("null")
        } else {
            val state = TraversalState()
            appendNode(output, root, 0, 0, state)
        }
        output.append('}')
        return output.toString()
    }

    private fun appendNode(
        output: StringBuilder,
        node: AccessibilityNodeInfo,
        depth: Int,
        indexInParent: Int,
        state: TraversalState
    ) {
        state.nodes += 1
        val bounds = Rect()
        try {
            node.getBoundsInScreen(bounds)
        } catch (_: Exception) {
        }

        output.append('{')
        output.append("\"depth\":").append(depth).append(',')
        output.append("\"indexInParent\":").append(indexInParent).append(',')
        output.append("\"text\":").append(JsonSupport.quote(safeText { node.text })).append(',')
        output.append("\"contentDescription\":")
            .append(JsonSupport.quote(safeText { node.contentDescription })).append(',')
        output.append("\"viewIdResourceName\":")
            .append(JsonSupport.quote(safeString { node.viewIdResourceName })).append(',')
        output.append("\"className\":").append(JsonSupport.quote(safeText { node.className })).append(',')
        output.append("\"boundsInScreen\":").append(JsonSupport.quote(bounds.flattenToString())).append(',')
        output.append("\"clickable\":").append(safeBoolean { node.isClickable }).append(',')
        output.append("\"editable\":").append(safeBoolean { node.isEditable }).append(',')
        output.append("\"scrollable\":").append(safeBoolean { node.isScrollable }).append(',')
        output.append("\"visibleToUser\":").append(safeBoolean { node.isVisibleToUser }).append(',')
        output.append("\"enabled\":").append(safeBoolean { node.isEnabled }).append(',')
        output.append("\"selected\":").append(safeBoolean { node.isSelected }).append(',')
        val childCount = try { node.childCount } catch (_: Exception) { 0 }
        output.append("\"childCount\":").append(childCount).append(',')
        output.append("\"truncated\":")

        val mustTruncate = depth >= MAX_DEPTH || state.nodes >= MAX_NODES
        output.append(mustTruncate)
        output.append(",\"children\":[")
        if (!mustTruncate) {
            var first = true
            for (index in 0 until childCount) {
                if (state.nodes >= MAX_NODES) break
                val child = try { node.getChild(index) } catch (_: Exception) { null }
                if (child != null) {
                    if (!first) output.append(',')
                    appendNode(output, child, depth + 1, index, state)
                    first = false
                    try {
                        child.recycle()
                    } catch (_: Exception) {
                    }
                }
            }
        }
        output.append("]}")
    }

    private fun safeText(block: () -> CharSequence?): CharSequence? {
        return try {
            val value = block() ?: return null
            if (value.length > MAX_TEXT_LENGTH) value.subSequence(0, MAX_TEXT_LENGTH) else value
        } catch (_: Exception) {
            null
        }
    }

    private fun safeString(block: () -> String?): String? {
        return try {
            val value = block() ?: return null
            if (value.length > MAX_TEXT_LENGTH) value.substring(0, MAX_TEXT_LENGTH) else value
        } catch (_: Exception) {
            null
        }
    }

    private fun safeBoolean(block: () -> Boolean): Boolean {
        return try { block() } catch (_: Exception) { false }
    }

    private fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date(timestamp))
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        TtsClient.setPlaybackStateListener(null)
        statusOverlay?.hide()
        statusOverlay = null
        handler.removeCallbacks(autoReadPoll)
        handler.removeCallbacksAndMessages(null)
        writer.shutdown()
        super.onDestroy()
    }

    private data class EventMetadata(
        val timestampEpochMs: Long,
        val eventType: Int,
        val eventTypeName: String,
        val packageName: String?,
        val className: CharSequence?,
        val contentChangeTypes: Int,
        val eventText: List<CharSequence>,
        val sourceJson: String
    ) {
        companion object {
            fun from(event: AccessibilityEvent): EventMetadata {
                val source = try { event.source } catch (_: Exception) { null }
                val sourceJson = if (source == null) {
                    "null"
                } else {
                    val result = buildSourceJson(source)
                    try {
                        source.recycle()
                    } catch (_: Exception) {
                    }
                    result
                }
                return EventMetadata(
                    timestampEpochMs = System.currentTimeMillis(),
                    eventType = event.eventType,
                    eventTypeName = AccessibilityEvent.eventTypeToString(event.eventType),
                    packageName = event.packageName?.toString(),
                    className = event.className,
                    contentChangeTypes = event.contentChangeTypes,
                    eventText = event.text.toList(),
                    sourceJson = sourceJson
                )
            }

            private fun buildSourceJson(source: AccessibilityNodeInfo): String {
                val bounds = Rect()
                try { source.getBoundsInScreen(bounds) } catch (_: Exception) {}
                return StringBuilder(512)
                    .append('{')
                    .append("\"text\":").append(JsonSupport.quote(safeValue { source.text })).append(',')
                    .append("\"contentDescription\":")
                    .append(JsonSupport.quote(safeValue { source.contentDescription })).append(',')
                    .append("\"viewIdResourceName\":")
                    .append(JsonSupport.quote(safeStringValue { source.viewIdResourceName })).append(',')
                    .append("\"className\":").append(JsonSupport.quote(safeValue { source.className })).append(',')
                    .append("\"boundsInScreen\":").append(JsonSupport.quote(bounds.flattenToString()))
                    .append('}')
                    .toString()
            }

            private fun safeValue(block: () -> CharSequence?): CharSequence? {
                return try { block() } catch (_: Exception) { null }
            }

            private fun safeStringValue(block: () -> String?): String? {
                return try { block() } catch (_: Exception) { null }
            }
        }
    }

    private class TraversalState(var nodes: Int = 0)

    companion object {
        private const val CHATGPT_PACKAGE = "com.openai.chatgpt"
        private const val SNAPSHOT_INTERVAL_MS = 350L
        private const val AUTO_READ_POLL_MS = 3_000L
        private const val REQUIRED_STABLE_POLLS = 2
        private const val FAILED_RETRY_DELAY_MS = 6_000L
        private const val CLIPBOARD_READ_DELAY_MS = 650L
        private const val CLIPBOARD_RETRY_DELAY_MS = 450L
        private const val CLIPBOARD_READ_ATTEMPTS = 4
        private const val TAG = "NahidaReader"
        private const val MAX_DEPTH = 50
        private const val MAX_NODES = 1500
        private const val MAX_TEXT_LENGTH = 8000
    }
}
