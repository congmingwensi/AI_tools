package com.nahida.gptprobe

import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect

object ChatGptMessageExtractor {
    data class Result(val text: String?, val reason: String)

    private data class TextPiece(val text: String, val ordinal: Int)
    private data class Candidate(val text: String, val lastTextOrdinal: Int)
    private data class Subtree(val hasCopy: Boolean, val pieces: List<TextPiece>)

    fun extract(root: AccessibilityNodeInfo): Result {
        val candidates = ArrayList<Candidate>()
        var nextOrdinal = 0

        fun visit(node: AccessibilityNodeInfo): Subtree {
            val description = safeString { node.contentDescription }?.trim().orEmpty()
            val text = safeString { node.text }?.trim().orEmpty()
            var hasCopy = isAnswerCopyLabel(description) || isAnswerCopyLabel(text)
            val pieces = ArrayList<TextPiece>()
            val editable = try { node.isEditable } catch (_: Exception) { false }
            if (!editable && text.isNotEmpty() && !isToolbarLabel(text) &&
                !isAnswerCopyLabel(text) && !isFooter(text)
            ) {
                val piece = TextPiece(text, nextOrdinal++)
                pieces.add(piece)
            }

            var childContainsTextAndCopy = false
            val childCount = try { node.childCount } catch (_: Exception) { 0 }
            for (index in 0 until childCount) {
                val child = try { node.getChild(index) } catch (_: Exception) { null }
                if (child != null) {
                    val childResult = visit(child)
                    if (childResult.hasCopy && childResult.pieces.isNotEmpty()) {
                        childContainsTextAndCopy = true
                    }
                    hasCopy = hasCopy || childResult.hasCopy
                    pieces.addAll(childResult.pieces)
                    try { child.recycle() } catch (_: Exception) {}
                }
            }

            if (hasCopy && pieces.isNotEmpty() && !childContainsTextAndCopy) {
                val combined = pieces.joinToString("\n\n") { it.text }.trim()
                if (combined.isNotEmpty()) {
                    candidates.add(Candidate(combined, pieces.last().ordinal))
                }
            }
            return Subtree(hasCopy, pieces)
        }

        visit(root)
        val latest = candidates.maxBy { it.lastTextOrdinal }
            ?: return Result(null, "当前界面还没有可确认完成的助手回答")

        return Result(latest.text, "已定位最新完整助手回答")
    }

    fun hasVisibleAnswerCopy(root: AccessibilityNodeInfo, visibleBounds: Rect): Boolean {
        val copy = findVisibleAnswerCopy(root, visibleBounds) ?: return false
        try { copy.recycle() } catch (_: Exception) {}
        return true
    }

    private fun findVisibleAnswerCopy(root: AccessibilityNodeInfo, visibleBounds: Rect): AccessibilityNodeInfo? {
        var latest: AccessibilityNodeInfo? = null

        fun visit(node: AccessibilityNodeInfo) {
            val description = safeString { node.contentDescription }?.trim().orEmpty()
            val text = safeString { node.text }?.trim().orEmpty()
            val bounds = Rect()
            val visible = try {
                node.getBoundsInScreen(bounds)
                node.isVisibleToUser && node.isEnabled && Rect.intersects(bounds, visibleBounds)
            } catch (_: Exception) { false }
            if (visible && (isAnswerCopyLabel(description) || isAnswerCopyLabel(text))) {
                try { latest?.recycle() } catch (_: Exception) {}
                latest = try { AccessibilityNodeInfo.obtain(node) } catch (_: Exception) { null }
            }

            val childCount = try { node.childCount } catch (_: Exception) { 0 }
            for (index in 0 until childCount) {
                val child = try { node.getChild(index) } catch (_: Exception) { null }
                if (child != null) {
                    visit(child)
                    try { child.recycle() } catch (_: Exception) {}
                }
            }
        }

        visit(root)
        return latest
    }

    /** Click the last on-screen answer Copy, excluding off-screen and Copy code buttons. */
    fun clickLatestAnswerCopy(root: AccessibilityNodeInfo, visibleBounds: Rect): Boolean {
        var current = findVisibleAnswerCopy(root, visibleBounds) ?: return false
        repeat(5) {
            val clickable = try { current.isClickable && current.isEnabled } catch (_: Exception) { false }
            if (clickable) {
                val clicked = try {
                    current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } catch (_: Exception) {
                    false
                }
                try { current.recycle() } catch (_: Exception) {}
                return clicked
            }
            val parent = try { current.parent } catch (_: Exception) { null }
            try { current.recycle() } catch (_: Exception) {}
            if (parent == null) return false
            current = parent
        }
        try { current.recycle() } catch (_: Exception) {}
        return false
    }

    private fun isAnswerCopyLabel(value: String): Boolean {
        return when (value.trim().toLowerCase()) {
            "\u590d\u5236", "\u590d\u5236\u56de\u7b54", "copy", "copy response" -> true
            else -> false
        }
    }

    private fun isFooter(value: String): Boolean =
        value.startsWith("ChatGPT 可能会出错") ||
            value.startsWith("ChatGPT can make mistakes")

    private fun isToolbarLabel(value: String): Boolean {
        return when (value.trim().toLowerCase()) {
            "\u6765\u6e90", "sources" -> true
            else -> false
        }
    }

    private fun safeString(block: () -> CharSequence?): String? =
        try { block()?.toString() } catch (_: Exception) { null }

}
