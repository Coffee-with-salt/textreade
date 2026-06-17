package com.textreader.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import com.textreader.app.MainActivity
import com.textreader.app.R
import com.textreader.app.util.TtsEngineManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * TTS 前台服务 - 支持息屏朗读、定时停止、循环、语速调节
 */
class TtsService : Service() {

    companion object {
        const val TAG = "TtsService"
        const val CHANNEL_ID = "tts_channel"
        const val NOTIFICATION_ID = 1001

        // 每次朗读的最大字符数（TTS 有上限，ColorOS 上 200 更稳）
        const val CHUNK_SIZE = 200

        // 错误恢复：单块最多重试次数
        private const val TTS_CHUNK_MAX_RETRIES = 2

        // TTS 引擎就绪失败重试延迟
        private const val TTS_INIT_RETRY_DELAY_MS = 500L
        private const val TTS_MAX_INIT_RETRIES = 5

        // 连续失败的块数超过此值，认为引擎挂了，停止播放
        private const val MAX_CONSECUTIVE_FAILURES = 10

        // TTS 错误码常量
        private const val TTS_ERROR_SUCCESS = 0
        private const val TTS_ERROR_SYNTHESIS = -3
    }

    inner class TtsBinder : Binder() {
        fun getService(): TtsService = this@TtsService
    }

    private val binder = TtsBinder()

    // TTS 引擎
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var ttsInitRetries = 0
    private var ttsInitError: String? = null
    private var lastDefaultEngine: String? = null
    private var lastEngineNames: List<String> = emptyList()
    // 用户选中的引擎包名
    private var userSelectedEngine: String? = null

    // 文本分块
    private var textChunks: List<String> = emptyList()
    private var currentChunkIndex = 0
    // 跳过已确认无法朗读的块（用块索引去重）
    private val badChunks = mutableSetOf<Int>()
    // 当前块连续失败次数
    private var chunkRetryCount = 0
    // 连续失败块数（用于判断引擎是否已挂）
    private var consecutiveFailures = 0
    // 已经按句号拆过的子块（无法再继续拆，引擎完全不能念的彻底坏块）
    private val deadChunks = mutableSetOf<Int>()

    // 朗读状态
    private val _state = MutableStateFlow(TtsState())
    val state: StateFlow<TtsState> = _state.asStateFlow()

    // 定时停止
    private var timerRunnable: Runnable? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    // 息屏唤醒锁（让CPU保持运行）
    private var wakeLock: PowerManager.WakeLock? = null

    // 协程作用域（用于加载用户引擎偏好）
    private val ttsEngineScope = CoroutineScope(Dispatchers.IO + Job())

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        createNotificationChannel()
        // 先把服务置为前台，避免系统在我们还没初始化好时杀掉它
        startForeground(NOTIFICATION_ID, buildNotification())
        // 加载用户选中的引擎
        loadUserEnginePreference()
        initTts()
    }

    /**
     * 加载用户保存的 TTS 引擎偏好
     */
    private fun loadUserEnginePreference() {
        try {
            ttsEngineScope.launch {
                TtsEngineManager.getSelectedEngineFlow(this@TtsService).collect { engine ->
                    engine?.let { selected ->
                        if (TtsEngineManager.isEngineAvailable(this@TtsService, selected)) {
                            userSelectedEngine = selected
                            Log.d(TAG, "loadUserEnginePreference: selected engine=$selected")
                        } else {
                            Log.w(TAG, "loadUserEnginePreference: engine $selected not available")
                            userSelectedEngine = null
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadUserEnginePreference failed: ${e.message}")
        }
    }

    // 已尝试失败的引擎包名（避免重复尝试）
    private val triedEngines = mutableSetOf<String?>()
    // 候选引擎列表
    private var pendingEngines: List<String?> = listOf(null)

    private fun initTts() {
        Log.d(TAG, "initTts: start, retries=$ttsInitRetries")

        // 第一轮：收集候选引擎列表
        if (pendingEngines.size <= 1 || pendingEngines.all { it in triedEngines }) {
            pendingEngines = collectEngineCandidates()
        }

        // 选下一个未尝试的引擎
        val engineToUse = pendingEngines.firstOrNull { it !in triedEngines }
        if (engineToUse != null) triedEngines.add(engineToUse)
        Log.d(TAG, "initTts: trying engine=$engineToUse, tried=$triedEngines")

        try {
            if (engineToUse != null) {
                tts = TextToSpeech(this, { status ->
                    handleTtsInitResult(status, engineToUse)
                }, engineToUse)
            } else {
                tts = TextToSpeech(this) { status ->
                    handleTtsInitResult(status, null)
                }
            }
            Log.d(TAG, "initTts: TextToSpeech instance created")
        } catch (e: Exception) {
            Log.e(TAG, "initTts: exception ${e.message}", e)
            ttsInitError = "TTS 初始化异常: ${e.message}"
            _state.value = _state.value.copy(errorMessage = ttsInitError)
            updateNotification()
        }
    }

    /**
     * 收集所有候选引擎，按优先级排序：
     * 1) 用户选中的引擎（如果已加载）
     * 2) 推荐引擎（微软离线 TTS / TTS Server）
     * 3) 其他已安装引擎
     * 4) null（系统默认）
     */
    private fun collectEngineCandidates(): List<String?> {
        val result = mutableListOf<String?>()
        result.add(null)

        var probe: TextToSpeech? = null
        try {
            probe = TextToSpeech(this) { /* no-op */ }
            val default = probe.defaultEngine
            if (!default.isNullOrBlank() && default !in result) {
                // 默认引擎放优先级最高（在用户选中引擎之后）
                val insertIdx = if (userSelectedEngine != null) 2 else 1
                result.add(insertIdx, default)
            }
            val engines = probe.engines
            for (e in engines) {
                if (e.name.isNotBlank() && e.name !in result) {
                    result.add(e.name)
                }
            }
            Log.d(TAG, "collectEngineCandidates: $result")
        } catch (e: Exception) {
            Log.e(TAG, "collectEngineCandidates failed: ${e.message}")
        } finally {
            try { probe?.stop(); probe?.shutdown() } catch (_: Exception) {}
        }
        return result
    }

    private fun handleTtsInitResult(status: Int, engine: String?) {
        Log.d(TAG, "handleTtsInitResult: status=$status, engine=$engine, tts==null? ${tts == null}")
        if (status == TextToSpeech.SUCCESS) {
            Log.d(TAG, "TTS init SUCCESS, engine=$engine")
            // 优先使用中文，回退英文，再回退系统默认
            val locales = listOf(Locale.CHINESE, Locale.US, Locale.ROOT, Locale.getDefault())
            var setOk = false
            for (loc in locales) {
                val r = tts?.setLanguage(loc) ?: TextToSpeech.LANG_NOT_SUPPORTED
                Log.d(TAG, "setLanguage($loc) -> $r")
                if (r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED) {
                    setOk = true
                    break
                }
            }
            if (!setOk) {
                Log.w(TAG, "没有合适语种，使用默认")
                tts?.setLanguage(Locale.getDefault())
            }

            isTtsReady = true
            ttsInitError = null
            ttsInitRetries = 0

            // 记录引擎信息（实例方法调用）
            tts?.let { current ->
                try {
                    lastDefaultEngine = current.defaultEngine
                    val engines = current.engines
                    lastEngineNames = engines.map { e -> e.name }
                    Log.d(TAG, "TTS engine ready, default=$lastDefaultEngine, installed=$lastEngineNames")
                } catch (e: Exception) {
                    Log.e(TAG, "collect engine info failed: ${e.message}")
                }
            }

            // 检测是否需要推荐 TTS Server
            val needRecommend = TtsEngineManager.needsTtsServerRecommendation(this)
            if (needRecommend) {
                Log.w(TAG, "TTS ready but no Microsoft TTS / TTS Server found, will recommend")
                _state.value = _state.value.copy(needsTtsServerRecommendation = true)
            }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d(TAG, "TTS onStart: $utteranceId")
                }

                override fun onDone(utteranceId: String?) {
                    Log.d(TAG, "TTS onDone: $utteranceId")
                    // 本块朗读成功，重置失败计数
                    chunkRetryCount = 0
                    consecutiveFailures = 0

                    val chunkIdx = utteranceId?.toIntOrNull() ?: return
                    val nextIdx = chunkIdx + 1
                    val position = minOf(nextIdx * CHUNK_SIZE, _state.value.totalChars)
                    _state.value = _state.value.copy(currentPosition = position)

                    if (nextIdx < textChunks.size) {
                        currentChunkIndex = nextIdx
                        speakChunk(nextIdx)
                    } else {
                        if (_state.value.isLooping) {
                            Log.d(TAG, "loop: 重新从头开始")
                            currentChunkIndex = 0
                            _state.value = _state.value.copy(currentPosition = 0)
                            badChunks.clear()
                            speakChunk(0)
                        } else {
                            _state.value = _state.value.copy(
                                isPlaying = false,
                                currentPosition = 0,
                                errorMessage = null
                            )
                            updateNotification()
                        }
                    }
                }

                @Deprecated("Deprecated in API 21+ but still required for older devices")
                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "TTS onError: $utteranceId (deprecated)")
                    handleTtsChunkError(utteranceId, -1)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    Log.e(TAG, "TTS onError: id=$utteranceId code=$errorCode")
                    handleTtsChunkError(utteranceId, errorCode)
                }
            })

            // 如果等待初始化期间已经有待播放的内容
            val pendingState = _state.value
            if (pendingState.pendingPlay && pendingState.text.isNotEmpty()) {
                _state.value = pendingState.copy(pendingPlay = false)
                startReading(pendingState.text, pendingState.currentPosition)
            }
        } else {
            // TTS 初始化失败 - 重试：尝试下一个候选引擎
            Log.e(TAG, "TTS init failed: status=$status, engine=$engine, retries=$ttsInitRetries")
            ttsInitError = "TTS 初始化失败 (status=$status, engine=$engine)"
            ttsInitRetries++

            // 当前引擎尝试失败，从候选列表里找下一个
            val hasNextCandidate = pendingEngines.any { it !in triedEngines }
            if (hasNextCandidate) {
                handler.postDelayed({
                    try { tts?.shutdown() } catch (_: Exception) {}
                    tts = null
                    initTts()
                }, TTS_INIT_RETRY_DELAY_MS)
            } else if (ttsInitRetries < TTS_MAX_INIT_RETRIES) {
                // 所有引擎都失败，重置尝试列表再来一轮
                triedEngines.clear()
                pendingEngines = listOf(null)
                handler.postDelayed({
                    try { tts?.shutdown() } catch (_: Exception) {}
                    tts = null
                    initTts()
                }, TTS_INIT_RETRY_DELAY_MS)
            } else {
                _state.value = _state.value.copy(
                    errorMessage = "$ttsInitError\n请到系统设置→文字转语音(TTS)服务中检查权限",
                    isPlaying = false
                )
                updateNotification()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    /**
     * 设置要朗读的文本（不立即播放）
     */
    fun setText(text: String, startPosition: Int = 0) {
        tts?.stop()
        _state.value = _state.value.copy(
            text = text,
            totalChars = text.length,
            currentPosition = startPosition,
            isPlaying = false,
            errorMessage = null
        )
        textChunks = splitText(text)
        currentChunkIndex = startPosition / CHUNK_SIZE
        badChunks.clear()
        deadChunks.clear()
        chunkRetryCount = 0
        consecutiveFailures = 0
    }

    /**
     * 开始朗读
     */
    fun startReading(text: String? = null, startPosition: Int? = null) {
        val actualText = text ?: _state.value.text
        val actualPos = startPosition ?: _state.value.currentPosition

        if (actualText.isEmpty()) {
            Log.w(TAG, "startReading: empty text, ignore")
            return
        }

        if (!isTtsReady || tts == null) {
            // TTS 还没初始化好，先标记等待
            Log.d(TAG, "startReading: TTS not ready, queue pending")
            _state.value = _state.value.copy(
                text = actualText,
                totalChars = actualText.length,
                currentPosition = actualPos,
                pendingPlay = true
            )
            return
        }

        tts?.stop()
        textChunks = splitText(actualText)
        currentChunkIndex = (actualPos / CHUNK_SIZE).coerceIn(0, textChunks.size - 1)
        badChunks.clear()
        deadChunks.clear()
        chunkRetryCount = 0
        consecutiveFailures = 0

        _state.value = _state.value.copy(
            text = actualText,
            totalChars = actualText.length,
            currentPosition = actualPos,
            isPlaying = true,
            errorMessage = null
        )

        // 获取 WakeLock（让 CPU 在息屏时继续工作）
        acquireWakeLock()

        speakChunk(currentChunkIndex)
        updateNotification()
    }

    /**
     * 暂停
     */
    fun pause() {
        tts?.stop()
        _state.value = _state.value.copy(isPlaying = false)
        releaseWakeLock()
        updateNotification()
    }

    /**
     * 恢复（从当前位置继续）
     */
    fun resume() {
        if (_state.value.text.isEmpty()) return
        if (!isTtsReady || tts == null) {
            _state.value = _state.value.copy(pendingPlay = true)
            return
        }
        _state.value = _state.value.copy(isPlaying = true, errorMessage = null)
        acquireWakeLock()
        speakChunk(currentChunkIndex)
        updateNotification()
    }

    /**
     * 停止
     */
    fun stop() {
        tts?.stop()
        cancelTimer()
        _state.value = _state.value.copy(
            isPlaying = false,
            currentPosition = 0
        )
        currentChunkIndex = 0
        releaseWakeLock()
        updateNotification()
    }

    /**
     * 设置语速 (0.5 ~ 2.0, 默认 1.0)
     */
    fun setSpeechRate(rate: Float) {
        val clamped = rate.coerceIn(0.25f, 4.0f)
        tts?.setSpeechRate(clamped)
        _state.value = _state.value.copy(speechRate = clamped)
        // 如果正在播放，重新开始当前块
        if (_state.value.isPlaying && isTtsReady) {
            tts?.stop()
            speakChunk(currentChunkIndex)
        }
    }

    /**
     * 设置循环
     */
    fun setLooping(loop: Boolean) {
        _state.value = _state.value.copy(isLooping = loop)
    }

    /**
     * 设置定时停止（分钟，0=不限时）
     */
    fun setTimer(minutes: Int) {
        cancelTimer()
        if (minutes > 0) {
            _state.value = _state.value.copy(
                timerMinutes = minutes,
                timerEndTime = System.currentTimeMillis() + minutes * 60_000L
            )
            val runnable = Runnable {
                pause()
                _state.value = _state.value.copy(timerMinutes = 0, timerEndTime = 0)
            }
            timerRunnable = runnable
            handler.postDelayed(runnable, minutes * 60_000L)
        } else {
            _state.value = _state.value.copy(timerMinutes = 0, timerEndTime = 0)
        }
    }

    /**
     * 跳转到指定位置
     */
    fun seekTo(position: Int) {
        val wasPlaying = _state.value.isPlaying
        tts?.stop()

        val clampedPos = position.coerceIn(0, _state.value.totalChars)
        currentChunkIndex = (clampedPos / CHUNK_SIZE).coerceIn(0, maxOf(0, textChunks.size - 1))
        _state.value = _state.value.copy(currentPosition = clampedPos)

        if (wasPlaying && isTtsReady) {
            speakChunk(currentChunkIndex)
        }
    }

    /**
     * 向前跳一段（10%）
     */
    fun skipForward() {
        val newPos = (_state.value.currentPosition + _state.value.totalChars / 10)
            .coerceAtMost(_state.value.totalChars)
        seekTo(newPos)
    }

    /**
     * 向后跳一段
     */
    fun skipBackward() {
        val newPos = (_state.value.currentPosition - _state.value.totalChars / 10)
            .coerceAtLeast(0)
        seekTo(newPos)
    }

    /**
     * 检查 TTS 引擎是否就绪
     */
    fun isReady(): Boolean = isTtsReady

    /**
     * 获取初始化错误信息
     */
    fun getInitError(): String? = ttsInitError

    /**
     * 诊断信息 - 用于 UI 显示 TTS 实际状态
     */
    fun getDiagnostics(): String {
        val pm = packageManager
        val engines: List<String> = lastEngineNames
        val defaultEngine: String? = lastDefaultEngine
        val voices: String = tts?.voices?.joinToString(", ") { voice ->
            "${voice.name}(${voice.locale})"
        } ?: "null"
        val currentLocale: String = try {
            tts?.voice?.locale?.toString() ?: "null"
        } catch (_: Exception) { "?" }
        return buildString {
            appendLine("=== TTS 诊断信息 ===")
            appendLine("【引擎就绪】 $isTtsReady")
            appendLine("【init 重试次数】 $ttsInitRetries")
            if (ttsInitError != null) appendLine("【init 错误】 $ttsInitError")
            appendLine("【用户选中的引擎】 ${userSelectedEngine ?: "（未选择，用推荐引擎）"}")
            appendLine("【当前默认引擎】 ${defaultEngine ?: "未设置"}")
            appendLine("【系统已安装引擎】")
            appendLine("  • " + if (engines.isEmpty()) "（无）" else engines.joinToString("\n  • "))
            appendLine("【当前 TTS 实例】 ${if (tts != null) "OK" else "null"}")
            appendLine("【当前 Locale】 $currentLocale")
            appendLine("【当前语速】 ${_state.value.speechRate}")
            appendLine("【已加载语音】 $voices")
            appendLine("【SDK 版本】 ${Build.VERSION.SDK_INT}")
            appendLine("【Service 状态】 playing=${_state.value.isPlaying}, text len=${_state.value.text.length}")
        }
    }

    /**
     * 切换 TTS 引擎
     * @param packageName 目标引擎包名，null 则使用推荐引擎
     */
    fun switchEngine(packageName: String?) {
        Log.d(TAG, "switchEngine: $packageName")
        ttsEngineScope.launch {
            TtsEngineManager.saveSelectedEngine(this@TtsService, packageName)
        }
        userSelectedEngine = packageName
        // 重新初始化 TTS（先关旧实例，再开新的）
        handler.post {
            try { tts?.stop() } catch (_: Exception) {}
            try { tts?.shutdown() } catch (_: Exception) {}
            tts = null
            isTtsReady = false
            initTts()
        }
    }

    // ========== 私有工具方法 ==========

    /**
     * 处理朗读过程中的错误。
     * 策略（越打越碎，绝不静默跳过）：
     * 1) 先重试整块
     * 2) 再失败 → 把块按句号拆成子块，按子块朗读
     * 3) 子块还失败 → 按逗号拆
     * 4) 实在拆不动了（< 5 字符）才彻底放弃那一段
     */
    private fun handleTtsChunkError(utteranceId: String?, errorCode: Int) {
        val chunkIdx = utteranceId?.toIntOrNull()
        if (chunkIdx == null) {
            Log.w(TAG, "handleTtsChunkError: 无效的 utteranceId=$utteranceId，停止")
            _state.value = _state.value.copy(
                isPlaying = false,
                errorMessage = "朗读出错(无效块id)"
            )
            updateNotification()
            return
        }

        consecutiveFailures++
        chunkRetryCount++

        // 引擎挂了：连续失败太多或 engine crash
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            Log.e(TAG, "handleTtsChunkError: 连续 $consecutiveFailures 块失败，尝试恢复 (code=$errorCode)")
            if (errorCode == -3) {
                // TTS 引擎进程可能已崩溃/被杀，尝试重建 TTS 实例
                handler.post { tryRecoverTtsEngine() }
            }
            _state.value = _state.value.copy(
                isPlaying = false,
                errorMessage = "TTS 引擎异常 (code=$errorCode)，请稍后重试"
            )
            releaseWakeLock()
            updateNotification()
            return
        }

        // 第 1 步：整块重试
        if (chunkRetryCount <= TTS_CHUNK_MAX_RETRIES) {
            Log.w(TAG, "handleTtsChunkError: 块 $chunkIdx 第 $chunkRetryCount 次重试 (code=$errorCode)")
            handler.postDelayed({
                if (isTtsReady && tts != null && _state.value.isPlaying) {
                    speakChunk(chunkIdx)
                }
            }, 200L * chunkRetryCount)
            return
        }

        // 第 2 步：按句号拆分子块（对失败的块做"更细粒度重读"）
        val original = textChunks.getOrNull(chunkIdx)
        if (original != null) {
            val splitResult = trySplitChunk(original)
            if (splitResult != null && splitResult.size > 1) {
                Log.w(TAG, "handleTtsChunkError: 块 $chunkIdx 拆成 ${splitResult.size} 个子块重读")
                textChunks = textChunks.toMutableList().also { it.removeAt(chunkIdx) }
                    .let { list ->
                        val newList = list.toMutableList()
                        newList.addAll(chunkIdx, splitResult)
                        newList
                    }
                badChunks.remove(chunkIdx)
                deadChunks.remove(chunkIdx)
                chunkRetryCount = 0
                // 关键：当前块索引要重新对齐（因为 textChunks 已改变）
                currentChunkIndex = chunkIdx
                handler.postDelayed({
                    if (isTtsReady && tts != null && _state.value.isPlaying) {
                        speakChunk(chunkIdx)
                    }
                }, 100L)
                return
            }
        }

        // 第 3 步：拆不动了（已经按标点细到极致），标记为死块，跳到下一块
        Log.w(TAG, "handleTtsChunkError: 块 $chunkIdx 拆无可拆，彻底跳过 (code=$errorCode)")
        deadChunks.add(chunkIdx)
        badChunks.add(chunkIdx)
        chunkRetryCount = 0

        val nextIdx = chunkIdx + 1
        if (nextIdx < textChunks.size) {
            currentChunkIndex = nextIdx
            val position = minOf(nextIdx * CHUNK_SIZE, _state.value.totalChars)
            _state.value = _state.value.copy(
                currentPosition = position,
                errorMessage = "本段无法朗读，已跳过"
            )
            speakChunk(nextIdx)
        } else {
            if (_state.value.isLooping) {
                Log.d(TAG, "loop: 重头开始（清空死块记忆）")
                deadChunks.clear()
                currentChunkIndex = 0
                _state.value = _state.value.copy(currentPosition = 0, errorMessage = null)
                speakChunk(0)
            } else {
                _state.value = _state.value.copy(
                    isPlaying = false,
                    currentPosition = 0,
                    errorMessage = null
                )
                updateNotification()
            }
        }
    }

    /**
     * 尝试把一块失败的文本拆成更小的子块。
     * 拆完按子块朗读。
     * 返回 null 表示已经拆无可拆。
     */
    private fun trySplitChunk(text: String): List<String>? {
        if (text.length < 5) return null  // 太短，拆不动
        // 优先按中文标点拆
        val punctChars = charArrayOf('。', '！', '？', '；', '\n', '.', '!', '?')
        val result = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            var j = i + 1
            while (j < text.length && j - i < 80) {
                if (text[j] in punctChars) {
                    j++
                    break
                }
                j++
            }
            val piece = text.substring(i, j).trim()
            if (piece.isNotEmpty() && piece.isNotBlank()) {
                result.add(piece)
            }
            i = j
        }
        return if (result.size > 1) result else null
    }

    private fun speakChunk(index: Int) {
        if (index >= textChunks.size || index < 0) {
            Log.w(TAG, "speakChunk: index out of range $index, size=${textChunks.size}")
            return
        }
        if (!isTtsReady || tts == null) {
            Log.w(TAG, "speakChunk: TTS not ready")
            return
        }

        // 跳过死块（已无法再拆的彻底坏块）
        if (index in deadChunks) {
            Log.w(TAG, "speakChunk: 跳过死块 index=$index")
            advanceToNextChunk(index)
            return
        }

        val chunk = textChunks[index]
        // 块里没有可朗读字符（清洗后空了），直接跳过
        if (chunk.isBlank()) {
            Log.w(TAG, "speakChunk: 块 $index 清洗后为空，跳过")
            advanceToNextChunk(index)
            return
        }
        Log.d(TAG, "speakChunk: index=$index len=${chunk.length}")

        // 应用语速
        tts?.setSpeechRate(_state.value.speechRate)

        val result = tts?.speak(
            chunk,
            TextToSpeech.QUEUE_FLUSH,
            null,
            index.toString()
        )
        if (result != TextToSpeech.SUCCESS) {
            Log.e(TAG, "speak() returned $result")
            _state.value = _state.value.copy(errorMessage = "朗读失败 ($result)")
        }
    }

    /**
     * 推进到下一块（保持朗读状态）
     */
    private fun advanceToNextChunk(currentIndex: Int) {
        val next = currentIndex + 1
        if (next < textChunks.size) {
            currentChunkIndex = next
            speakChunk(next)
        } else if (_state.value.isLooping) {
            currentChunkIndex = 0
            _state.value = _state.value.copy(currentPosition = 0)
            speakChunk(0)
        } else {
            _state.value = _state.value.copy(isPlaying = false, errorMessage = null)
            updateNotification()
        }
    }

    /**
     * 将长文本分割成适合 TTS 的小块
     * 关键改进：
     * 1) 清洗 emoji / 控制字符 / 不可朗读符号（避免 ColorOS 合成 -3）
     * 2) 按标点优先断句，句号/问号/感叹号/分号/换行 都行
     * 3) 单块不超过 CHUNK_SIZE 字符
     */
    private fun splitText(text: String): List<String> {
        if (text.isEmpty()) return emptyList()

        // 1) 文本清洗：把容易让 TTS 合成失败的内容替换/删除
        val cleaned = cleanTextForTts(text)

        // 2) 分块
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < cleaned.length) {
            var end = minOf(start + CHUNK_SIZE, cleaned.length)
            // 尝试在句子结束处断开（向前 60 字符内找最近的标点）
            if (end < cleaned.length) {
                val searchStart = maxOf(start, end - 60)
                val lastPunct = cleaned.lastIndexOfAny(
                    charArrayOf('。', '！', '？', '\n', '；'),
                    end
                )
                val lastAsciiPunct = cleaned.lastIndexOfAny(
                    charArrayOf('.', '!', '?', ';'),
                    end
                )
                val bestPunct = maxOf(lastPunct, lastAsciiPunct)
                if (bestPunct > searchStart) {
                    end = bestPunct + 1
                }
            }
            val piece = cleaned.substring(start, end).trim()
            if (piece.isNotEmpty() && piece.isNotBlank()) {
                chunks.add(piece)
            }
            start = end
        }
        Log.d(TAG, "splitText: 原长 ${text.length} → 清洗后 ${cleaned.length} → ${chunks.size} 块")
        return chunks
    }

    /**
     * 清洗文本：
     * - 替换全角空白为半角
     * - 合并连续换行/空格
     * - 删除控制字符（\u0000-\u001F，但保留 \n 和 \t）
     * - 保留中日韩统一表意文字、ASCII 字母数字、基本标点
     * - 删除 emoji（防止 ColorOS 合成失败 -3）
     */
    private fun cleanTextForTts(text: String): String {
        val sb = StringBuilder(text.length)
        var lastWasSpace = false
        for (ch in text) {
            val c = ch.code
            // 1. 控制字符（除 \n \t 外全删）
            if (c < 0x20 && c != 0x0A && c != 0x09) continue
            // 2. 私有使用区
            if (c in 0xE000..0xF8FF) continue
            // 3. 代理对（高/低）— 主要是 emoji，跳过
            if (c in 0xD800..0xDBFF) continue
            if (c in 0xDC00..0xDFFF) continue
            // 4. emoji 专用区段
            if (c in 0x1F300..0x1FAFF) continue   // 各种符号 / 表情
            if (c in 0x1F600..0x1F64F) continue   // 表情
            if (c in 0x1F680..0x1F6FF) continue   // 交通
            if (c in 0x1F900..0x1F9FF) continue   // 补充符号
            if (c in 0x2600..0x27BF) {
                // 这个区段包含一些常用符号（©®™ 等），但也含杂项符号
                // 只保留已知安全的：© ® ™ ° ± × ÷
                if (c !in listOf(0x00A9, 0x00AE, 0x2122, 0x00B0, 0x00B1, 0x00D7, 0x00F7)) {
                    continue
                }
            }
            // 5. 合并空白
            val isSpace = c == 0x20 || c == 0x09 || c == 0x0A || c == 0x3000
            if (isSpace) {
                if (!lastWasSpace) sb.append(' ')
                lastWasSpace = true
                continue
            }
            lastWasSpace = false
            sb.append(ch)
        }
        return sb.toString()
    }

    private fun cancelTimer() {
        timerRunnable?.let { handler.removeCallbacks(it) }
        timerRunnable = null
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "TextReader::TtsWakeLock"
        )
        wakeLock?.acquire(10 * 60 * 60 * 1000L) // 最多10小时
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null
    }

    // ========== 通知 ==========

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "朗读服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "文本朗读后台服务"
                setSound(null, null)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isPlaying = _state.value.isPlaying
        val title = if (isPlaying) "正在朗读" else "已暂停"
        val err = _state.value.errorMessage
        val content = err ?: "文本朗读器"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    /**
     * 尝试恢复 TTS 引擎：当 TTS 返回 code=-3（引擎进程崩溃/失联）时调用。
     * 1) 先关闭旧实例
     * 2) 等 1 秒让系统清理
     * 3) 重新初始化
     * 4) 如果恢复成功且正在播放，继续从当前位置朗读
     */
    private fun tryRecoverTtsEngine() {
        Log.d(TAG, "tryRecoverTtsEngine: 开始恢复")
        // 关闭旧实例
        try { tts?.stop() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
        isTtsReady = false

        handler.postDelayed({
            val wasPlaying = _state.value.isPlaying
            val savedPosition = _state.value.currentPosition
            val savedText = _state.value.text

            // 重新初始化 TTS
            initTts()

            // initTts 是异步的，等它回调成功后 startReading 会通过 pendingPlay 机制触发
            if (wasPlaying) {
                handler.postDelayed({
                    if (isTtsReady && tts != null) {
                        Log.d(TAG, "tryRecoverTtsEngine: 恢复成功，继续朗读 pos=$savedPosition")
                        startReading(savedText, savedPosition)
                    } else {
                        Log.e(TAG, "tryRecoverTtsEngine: 恢复失败")
                        _state.value = _state.value.copy(
                            errorMessage = "TTS 引擎恢复失败，请手动重启播放"
                        )
                        updateNotification()
                    }
                }, 3000L)
            }
        }, 1000L)
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        try { tts?.stop() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
        isTtsReady = false
        ttsEngineScope.coroutineContext[Job]?.cancel()
        cancelTimer()
        releaseWakeLock()
        super.onDestroy()
    }
}

/**
 * TTS 状态数据类
 */
data class TtsState(
    val text: String = "",
    val totalChars: Int = 0,
    val currentPosition: Int = 0,
    val isPlaying: Boolean = false,
    val isLooping: Boolean = false,
    val speechRate: Float = 1.0f,
    val timerMinutes: Int = 0,
    val timerEndTime: Long = 0,
    val pendingPlay: Boolean = false,
    val errorMessage: String? = null,
    val needsTtsServerRecommendation: Boolean = false
) {
    val progress: Float
        get() = if (totalChars > 0) currentPosition.toFloat() / totalChars else 0f
}
