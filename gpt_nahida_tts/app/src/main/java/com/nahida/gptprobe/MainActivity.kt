package com.nahida.gptprobe

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var endpointInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var autoReadSwitch: Switch
    private lateinit var serviceStatusView: TextView
    private lateinit var runtimeStatusView: TextView
    private lateinit var latestTextView: TextView
    private lateinit var countView: TextView

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)
        setContentView(buildContentView())
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refreshRunnable)
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    private fun buildContentView(): ScrollView {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(28))
        }

        container.addView(TextView(this).apply {
            text = "GPT 纳西妲自动朗读"
            textSize = 23f
            setTextColor(Color.rgb(25, 25, 25))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(14))
        }, fullWidth())

        container.addView(TextView(this).apply {
            text = "仅在 ChatGPT 前台时，每 3 秒检查屏幕内可见的回答复制按钮。复制完整内容后，未朗读过的回答会自动朗读，历史回答也适用。"
            textSize = 15f
            setTextColor(Color.DKGRAY)
            setLineSpacing(0f, 1.18f)
            setPadding(0, 0, 0, dp(18))
        }, fullWidth())

        container.addView(TextView(this).apply {
            text = "本应用仅服务于ml妲厨，沾屎妲厨不配"
            textSize = 15f
            setTextColor(Color.RED)
            setPadding(0, 0, 0, dp(10))
        }, fullWidth())

        serviceStatusView = infoTextView(dp(10))
        container.addView(serviceStatusView, fullWidth())

        runtimeStatusView = infoTextView(dp(10))
        container.addView(runtimeStatusView, fullWidth())

        autoReadSwitch = Switch(this).apply {
            text = "启用自动朗读"
            textSize = 17f
            isChecked = AppSettings.isAutoReadEnabled(this@MainActivity)
            setPadding(dp(8), dp(8), dp(8), dp(12))
            setOnCheckedChangeListener { _, checked ->
                AppSettings.setAutoReadEnabled(this@MainActivity, checked)
                AppSettings.setStatus(
                    this@MainActivity,
                    if (checked) "自动朗读已开启，等待 ChatGPT" else "自动朗读已暂停"
                )
            }
        }
        container.addView(autoReadSwitch, fullWidth())

        container.addView(TextView(this).apply {
            text = "电脑 TTS 接口"
            textSize = 16f
            setTextColor(Color.rgb(35, 35, 35))
            setPadding(0, dp(8), 0, dp(5))
        }, fullWidth())

        endpointInput = EditText(this).apply {
            setText(AppSettings.endpoint(this@MainActivity))
            hint = "http://电脑IP:5002/generate_audio"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            textSize = 15f
        }
        container.addView(endpointInput, fullWidth())

        container.addView(TextView(this).apply {
            text = "接口密钥"
            textSize = 16f
            setTextColor(Color.rgb(35, 35, 35))
            setPadding(0, dp(8), 0, dp(5))
        }, fullWidth())

        tokenInput = EditText(this).apply {
            setText(AppSettings.apiToken(this@MainActivity))
            hint = "用于保护公网 TTS 接口"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            textSize = 15f
        }
        container.addView(tokenInput, fullWidth())

        container.addView(actionButton("保存接口设置") {
            saveSettings()
            Toast.makeText(this, "接口地址已保存", Toast.LENGTH_SHORT).show()
        }, buttonLayout(dp(8)))

        container.addView(actionButton("测试 TTS 连接并播放") {
            saveSettings()
            AppSettings.setStatus(this, "正在进行 TTS 测试…")
            TtsClient.speak(
                this,
                "你好呀，纳西妲语音连接成功了。",
                onStarted = {
                    Toast.makeText(this, "已收到音频，开始播放", Toast.LENGTH_SHORT).show()
                },
                onCompleted = {
                    Toast.makeText(this, "测试音频播放完成", Toast.LENGTH_SHORT).show()
                },
                onError = { message ->
                    AppSettings.setStatus(this, "TTS 测试失败：$message")
                    Toast.makeText(this, "测试失败：$message", Toast.LENGTH_LONG).show()
                }
            )
        }, buttonLayout(dp(12)))

        container.addView(actionButton("打开无障碍设置") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, buttonLayout(dp(12)))

        container.addView(TextView(this).apply {
            text = "最近识别到的助手回答"
            textSize = 16f
            setTextColor(Color.rgb(35, 35, 35))
            setPadding(0, dp(8), 0, dp(5))
        }, fullWidth())

        latestTextView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setBackgroundColor(Color.rgb(242, 242, 242))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        container.addView(latestTextView, fullWidth())

        container.addView(actionButton("允许重新朗读历史回答") {
            AppSettings.clearSpokenHistory(this)
            Toast.makeText(this, "已清空朗读去重记录", Toast.LENGTH_SHORT).show()
        }, buttonLayout(dp(18)))

        container.addView(TextView(this).apply {
            text = "诊断工具（可选）"
            textSize = 18f
            setTextColor(Color.rgb(35, 35, 35))
            setPadding(0, dp(4), 0, dp(8))
        }, fullWidth())

        countView = infoTextView(dp(8))
        container.addView(countView, fullWidth())

        container.addView(actionButton("开始记录 Accessibility JSON") {
            ProbeStorage.setRecording(this, true)
            if (!isProbeServiceEnabled()) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }, buttonLayout(dp(7)))

        container.addView(actionButton("停止记录") {
            ProbeStorage.setRecording(this, false)
        }, buttonLayout(dp(7)))

        container.addView(actionButton("导出 JSON") { exportJson() }, buttonLayout(dp(7)))

        container.addView(actionButton("清空诊断数据") {
            AlertDialog.Builder(this)
                .setTitle("清空诊断数据？")
                .setMessage("此操作只删除本应用私有目录中的 Accessibility 日志。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空") { _, _ -> ProbeStorage.clear(this) }
                .show()
        }, buttonLayout(0))

        return ScrollView(this).apply { addView(container) }
    }

    private fun infoTextView(bottomPadding: Int): TextView = TextView(this).apply {
        textSize = 15f
        setPadding(8, 4, 8, bottomPadding)
    }

    private fun actionButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 16f
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun fullWidth(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun buttonLayout(bottomMargin: Int): LinearLayout.LayoutParams =
        fullWidth().apply { this.bottomMargin = bottomMargin }

    private fun saveSettings() {
        AppSettings.setEndpoint(this, endpointInput.text.toString())
        AppSettings.setApiToken(this, tokenInput.text.toString())
        AppSettings.setAutoReadEnabled(this, autoReadSwitch.isChecked)
    }

    private fun refreshStatus() {
        val enabled = isProbeServiceEnabled()
        serviceStatusView.text = "无障碍服务：${if (enabled) "已启用" else "未启用（请先打开设置）"}"
        serviceStatusView.setTextColor(
            if (enabled) Color.rgb(0, 120, 70) else Color.rgb(180, 70, 0)
        )
        runtimeStatusView.text = "运行状态：${AppSettings.status(this)}"
        val latest = AppSettings.latestText(this)
        latestTextView.text = if (latest.isBlank()) "尚未识别到完整助手回答" else latest
        countView.text = "诊断记录：${if (ProbeStorage.isRecording(this)) "正在记录" else "已停止"}；已保存 ${ProbeStorage.recordCount(this)} 条"
    }

    private fun isProbeServiceEnabled(): Boolean {
        val expected = ComponentName(this, AccessibilityProbeService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun exportJson() {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "chatgpt-accessibility-$timestamp.json")
        }
        startActivityForResult(intent, REQUEST_EXPORT_JSON)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXPORT_JSON || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(ProbeStorage.exportJson(this).toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(this, "JSON 已导出", Toast.LENGTH_LONG).show()
        } catch (error: Exception) {
            Toast.makeText(this, "导出失败：${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val REQUEST_EXPORT_JSON = 1001
    }
}
