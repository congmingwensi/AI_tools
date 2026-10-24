package com.nahida.gptprobe

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object TtsClient {
    enum class PlaybackState { WAITING, CONNECTING, READING, PAUSED }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var playingFile: File? = null
    private var activeSession: ChunkSession? = null
    @Volatile private var busy = false
    @Volatile private var playbackState = PlaybackState.WAITING
    @Volatile private var playbackStateListener: ((PlaybackState) -> Unit)? = null

    fun isBusy(): Boolean = busy

    fun togglePauseResume(context: Context): Boolean {
        val currentPlayer = player ?: return false
        return try {
            when (playbackState) {
                PlaybackState.READING -> {
                    currentPlayer.pause()
                    notifyPlaybackState(PlaybackState.PAUSED)
                    AppSettings.setStatus(context.applicationContext, "朗读已暂停，点击悬浮条继续")
                    Log.i(TAG, "playback paused")
                    true
                }
                PlaybackState.PAUSED -> {
                    currentPlayer.start()
                    notifyPlaybackState(PlaybackState.READING)
                    AppSettings.setStatus(context.applicationContext, "继续朗读最新回答")
                    Log.i(TAG, "playback resumed")
                    true
                }
                else -> false
            }
        } catch (error: Exception) {
            Log.e(TAG, "pause/resume failed", error)
            false
        }
    }

    fun setPlaybackStateListener(listener: ((PlaybackState) -> Unit)?) {
        playbackStateListener = listener
        mainHandler.post {
            listener?.invoke(playbackState)
        }
    }

    private fun speakChunked(
        context: Context,
        text: String,
        onStarted: () -> Unit,
        onCompleted: () -> Unit,
        onError: (String) -> Unit
    ) {
        val appContext = context.applicationContext
        val endpoint = AppSettings.endpoint(appContext)
        val apiToken = AppSettings.apiToken(appContext)
        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            onError("接口地址必须以 http:// 或 https:// 开头")
            return
        }
        if (busy) {
            onError("已有一条语音正在生成或播放")
            return
        }
        if (apiToken.isBlank()) {
            onError("请先填写接口密钥")
            return
        }

        val chunks = splitForTts(text)
        if (chunks.isEmpty()) {
            onError("没有可朗读的文字")
            return
        }
        busy = true
        notifyPlaybackState(PlaybackState.CONNECTING)
        val session = ChunkSession(
            context = appContext,
            endpoint = endpoint,
            apiToken = apiToken,
            chunks = chunks,
            onStarted = onStarted,
            onCompleted = onCompleted,
            onError = onError
        )
        activeSession = session
        Log.i(TAG, "chunked request queued chars=${text.length} chunks=${chunks.size} endpoint=$endpoint")
        downloadChunk(session, 0)
    }

    private fun downloadChunk(session: ChunkSession, index: Int) {
        if (activeSession !== session || session.failed || index !in session.chunks.indices) return
        executor.execute {
            try {
                val file = requestAudio(
                    session.context,
                    session.endpoint,
                    session.apiToken,
                    session.chunks[index]
                )
                mainHandler.post { onChunkDownloaded(session, index, file) }
            } catch (error: Exception) {
                mainHandler.post { failSession(session, error.message ?: error.javaClass.simpleName) }
            }
        }
    }

    private fun onChunkDownloaded(session: ChunkSession, index: Int, file: File) {
        if (activeSession !== session || session.failed) {
            file.delete()
            return
        }
        Log.i(TAG, "chunk downloaded index=${index + 1}/${session.chunks.size} bytes=${file.length()}")
        if (index == 0 || session.waitingForChunk == index) {
            session.waitingForChunk = -1
            playChunk(session, index, file)
        } else {
            session.prefetchedFile?.delete()
            session.prefetchedFile = file
            session.prefetchedIndex = index
        }
    }

    private fun playChunk(session: ChunkSession, index: Int, file: File) {
        if (activeSession !== session || session.failed) {
            file.delete()
            return
        }
        session.currentIndex = index
        player?.release()
        playingFile?.delete()
        val newPlayer = MediaPlayer()
        player = newPlayer
        playingFile = file
        newPlayer.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        newPlayer.setDataSource(file.absolutePath)
        newPlayer.setOnPreparedListener {
            if (activeSession !== session) return@setOnPreparedListener
            it.start()
            notifyPlaybackState(PlaybackState.READING)
            if (!session.started) {
                session.started = true
                session.onStarted()
            }
            AppSettings.setStatus(
                session.context,
                "正在朗读第 ${index + 1}/${session.chunks.size} 段"
            )
            val nextIndex = index + 1
            if (nextIndex < session.chunks.size &&
                session.prefetchedIndex != nextIndex && session.downloadingIndex != nextIndex
            ) {
                session.downloadingIndex = nextIndex
                downloadChunk(session, nextIndex)
            }
        }
        newPlayer.setOnCompletionListener {
            it.release()
            if (player === it) player = null
            file.delete()
            if (playingFile === file) playingFile = null
            val nextIndex = index + 1
            if (nextIndex >= session.chunks.size) {
                completeSession(session)
            } else if (session.prefetchedIndex == nextIndex && session.prefetchedFile != null) {
                val nextFile = session.prefetchedFile!!
                session.prefetchedFile = null
                session.prefetchedIndex = -1
                playChunk(session, nextIndex, nextFile)
            } else {
                session.waitingForChunk = nextIndex
                notifyPlaybackState(PlaybackState.CONNECTING)
                AppSettings.setStatus(session.context, "正在生成第 ${nextIndex + 1}/${session.chunks.size} 段")
            }
        }
        newPlayer.setOnErrorListener { mediaPlayer, what, extra ->
            mediaPlayer.release()
            if (player === mediaPlayer) player = null
            file.delete()
            if (playingFile === file) playingFile = null
            failSession(session, "播放失败：$what/$extra")
            true
        }
        newPlayer.prepareAsync()
    }

    private fun completeSession(session: ChunkSession) {
        if (activeSession !== session) return
        session.prefetchedFile?.delete()
        activeSession = null
        busy = false
        notifyPlaybackState(PlaybackState.WAITING)
        AppSettings.setStatus(session.context, "朗读完成，继续监听 ChatGPT")
        Log.i(TAG, "chunked playback completed chunks=${session.chunks.size}")
        session.onCompleted()
    }

    private fun failSession(session: ChunkSession, message: String) {
        if (activeSession !== session || session.failed) return
        session.failed = true
        player?.release()
        player = null
        playingFile?.delete()
        playingFile = null
        session.prefetchedFile?.delete()
        activeSession = null
        busy = false
        notifyPlaybackState(PlaybackState.WAITING)
        Log.e(TAG, "chunked TTS failed message=$message")
        session.onError(message)
    }

    private fun splitForTts(source: String): List<String> {
        val text = source.trim()
        if (text.isEmpty()) return emptyList()
        val chunks = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + MAX_CHUNK_CHARS, text.length)
            if (end < text.length) {
                var split = -1
                for (index in end - 1 downTo start + MIN_CHUNK_CHARS) {
                    if (text[index] in CHUNK_BOUNDARIES) {
                        split = index + 1
                        break
                    }
                }
                if (split > start) end = split
                if (end < text.length && end > start && Character.isHighSurrogate(text[end - 1])) {
                    end -= 1
                }
            }
            val chunk = text.substring(start, end).trim()
            if (chunk.isNotEmpty()) chunks.add(chunk)
            start = end
            while (start < text.length && text[start].isWhitespace()) start += 1
        }
        return chunks
    }

    fun speak(
        context: Context,
        text: String,
        onStarted: () -> Unit,
        onCompleted: () -> Unit,
        onError: (String) -> Unit
    ) {
        val appContext = context.applicationContext
        val endpoint = AppSettings.endpoint(appContext)
        val apiToken = AppSettings.apiToken(appContext)
        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            busy = false
            onError("接口地址必须以 http:// 或 https:// 开头")
            return
        }
        if (busy) {
            onError("已有一条语音正在生成或播放")
            return
        }
        if (apiToken.isBlank()) {
            busy = false
            onError("请先填写接口密钥")
            return
        }

        busy = true
        notifyPlaybackState(PlaybackState.CONNECTING)
        Log.i(TAG, "request queued chars=${text.length} endpoint=$endpoint")
        executor.execute {
            try {
                AppSettings.setStatus(appContext, "正在请求电脑生成语音…")
                val audioFile = requestAudio(appContext, endpoint, apiToken, text)
                Log.i(TAG, "audio downloaded bytes=${audioFile.length()}")
                mainHandler.post {
                    try {
                        playFile(appContext, audioFile, onStarted, onCompleted, onError)
                    } catch (error: Exception) {
                        audioFile.delete()
                        busy = false
                        notifyPlaybackState(PlaybackState.WAITING)
                        Log.e(TAG, "player setup failed", error)
                        onError(error.message ?: error.javaClass.simpleName)
                    }
                }
            } catch (error: Exception) {
                mainHandler.post {
                    busy = false
                    notifyPlaybackState(PlaybackState.WAITING)
                    Log.e(TAG, "request failed", error)
                    onError(error.message ?: error.javaClass.simpleName)
                }
            }
        }
    }

    private fun requestAudio(
        context: Context,
        endpoint: String,
        apiToken: String,
        text: String
    ): File {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 12_000
            connection.readTimeout = 300_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "audio/*")
            connection.setRequestProperty("X-Nahida-Token", apiToken)
            val body = ("{\"text\":" + JsonSupport.quote(text) +
                ",\"is_play\":false,\"return_audio\":true}").toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }

            val status = connection.responseCode
            if (status !in 200..299) {
                val message = connection.errorStream?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText().take(500) }.orEmpty()
                throw IllegalStateException("TTS HTTP $status ${message.trim()}")
            }
            val contentType = connection.contentType.orEmpty().toLowerCase()
            if (contentType.contains("application/json")) {
                val message = connection.inputStream.bufferedReader(Charsets.UTF_8)
                    .use { it.readText().take(500) }
                throw IllegalStateException("服务仍返回 JSON，请重启修改后的 call_vits.py：$message")
            }

            val directory = File(context.cacheDir, "tts_audio")
            if (!directory.exists()) directory.mkdirs()
            val extension = if (contentType.contains("mpeg")) ".mp3" else ".wav"
            val target = File(directory, "reply-${System.currentTimeMillis()}$extension")
            connection.inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            if (target.length() == 0L) {
                target.delete()
                throw IllegalStateException("TTS 返回了空音频")
            }
            return target
        } finally {
            connection.disconnect()
        }
    }

    private fun playFile(
        context: Context,
        file: File,
        onStarted: () -> Unit,
        onCompleted: () -> Unit,
        onError: (String) -> Unit
    ) {
        player?.release()
        playingFile?.delete()
        val newPlayer = MediaPlayer()
        player = newPlayer
        playingFile = file
        newPlayer.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        newPlayer.setDataSource(file.absolutePath)
        newPlayer.setOnPreparedListener {
            it.start()
            notifyPlaybackState(PlaybackState.READING)
            Log.i(TAG, "playback started durationMs=${it.duration}")
            AppSettings.setStatus(context, "正在朗读最新回答")
            onStarted()
        }
        newPlayer.setOnCompletionListener {
            it.release()
            if (player === it) player = null
            file.delete()
            if (playingFile === file) playingFile = null
            busy = false
            notifyPlaybackState(PlaybackState.WAITING)
            Log.i(TAG, "playback completed")
            AppSettings.setStatus(context, "朗读完成，继续监听 ChatGPT")
            onCompleted()
        }
        newPlayer.setOnErrorListener { mediaPlayer, what, extra ->
            mediaPlayer.release()
            if (player === mediaPlayer) player = null
            file.delete()
            if (playingFile === file) playingFile = null
            busy = false
            notifyPlaybackState(PlaybackState.WAITING)
            Log.e(TAG, "playback error what=$what extra=$extra")
            onError("播放失败：$what/$extra")
            true
        }
        newPlayer.prepareAsync()
    }

    private fun notifyPlaybackState(state: PlaybackState) {
        playbackState = state
        playbackStateListener?.invoke(state)
    }

    private data class ChunkSession(
        val context: Context,
        val endpoint: String,
        val apiToken: String,
        val chunks: List<String>,
        val onStarted: () -> Unit,
        val onCompleted: () -> Unit,
        val onError: (String) -> Unit,
        var currentIndex: Int = -1,
        var downloadingIndex: Int = 0,
        var prefetchedIndex: Int = -1,
        var prefetchedFile: File? = null,
        var waitingForChunk: Int = -1,
        var started: Boolean = false,
        var failed: Boolean = false
    )

    private const val MAX_CHUNK_CHARS = 280
    private const val MIN_CHUNK_CHARS = 100
    private const val CHUNK_BOUNDARIES = "。！？!?；;，,：:\n"
    private const val TAG = "NahidaReader"
}
