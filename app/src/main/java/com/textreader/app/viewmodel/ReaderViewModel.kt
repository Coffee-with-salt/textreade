package com.textreader.app.viewmodel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.textreader.app.data.db.BookEntity
import com.textreader.app.data.repository.BookRepository
import com.textreader.app.service.TtsService
import com.textreader.app.service.TtsState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val repository: BookRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _readerUiState = MutableStateFlow(ReaderUiState())
    val readerUiState: StateFlow<ReaderUiState> = _readerUiState.asStateFlow()

    private val _ttsState = MutableStateFlow(TtsState())
    val ttsState: StateFlow<TtsState> = _ttsState.asStateFlow()

    private var ttsService: TtsService? = null
    private var currentBook: BookEntity? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as TtsService.TtsBinder).getService()
            ttsService = service

            // 开始收集 TTS 状态
            viewModelScope.launch {
                service.state.collect { state ->
                    _ttsState.value = state
                    // 同步 TTS Server 推荐状态到 ViewModel
                    if (state.needsTtsServerRecommendation) {
                        _readerUiState.value = _readerUiState.value.copy(needsTtsServerGuide = true)
                    }
                    // 定期保存进度
                    if (state.isPlaying && state.currentPosition > 0) {
                        currentBook?.let { book ->
                            if (state.currentPosition % 1000 < 50) {
                                repository.updateProgress(book.id, state.currentPosition)
                            }
                        }
                    }
                }
            }

            // 如果有待加载的书
            _readerUiState.value.pendingBook?.let { book ->
                loadBookIntoService(book)
                _readerUiState.value = _readerUiState.value.copy(pendingBook = null)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            ttsService = null
        }
    }

    init {
        bindTtsService()
    }

    private fun bindTtsService() {
        val intent = Intent(context, TtsService::class.java)
        context.startForegroundService(intent)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    /**
     * 加载书籍（首次或切换书）
     */
    fun loadBook(bookId: Long) {
        viewModelScope.launch {
            _readerUiState.value = _readerUiState.value.copy(isLoading = true)
            try {
                val book = repository.getBookById(bookId)
                    ?: throw Exception("找不到该书")
                currentBook = book
                val text = repository.loadBookText(book)

                _readerUiState.value = _readerUiState.value.copy(
                    isLoading = false,
                    bookTitle = book.title,
                    fullText = text,
                    errorMessage = null
                )

                if (ttsService != null) {
                    loadBookIntoService(book)
                } else {
                    // 服务还没连上，先缓存
                    _readerUiState.value = _readerUiState.value.copy(pendingBook = book)
                }
            } catch (e: Exception) {
                _readerUiState.value = _readerUiState.value.copy(
                    isLoading = false,
                    errorMessage = e.message
                )
            }
        }
    }

    private fun loadBookIntoService(book: BookEntity) {
        val text = _readerUiState.value.fullText
        if (text.isEmpty()) return
        ttsService?.setText(text, book.lastPosition)
    }

    // ========== TTS 控制 ==========

    fun play() {
        if (_ttsState.value.isPlaying) {
            ttsService?.pause()
        } else {
            if (_ttsState.value.text.isEmpty()) {
                val text = _readerUiState.value.fullText
                val pos = currentBook?.lastPosition ?: 0
                ttsService?.startReading(text, pos)
            } else {
                ttsService?.resume()
            }
        }
    }

    fun stop() {
        ttsService?.stop()
        // 保存进度
        currentBook?.let { book ->
            viewModelScope.launch {
                repository.updateProgress(book.id, _ttsState.value.currentPosition)
            }
        }
    }

    fun skipForward() = ttsService?.skipForward()
    fun skipBackward() = ttsService?.skipBackward()

    fun setSpeechRate(rate: Float) = ttsService?.setSpeechRate(rate)

    fun setLooping(loop: Boolean) = ttsService?.setLooping(loop)

    fun setTimer(minutes: Int) = ttsService?.setTimer(minutes)

    fun seekTo(position: Int) = ttsService?.seekTo(position)

    /**
     * 切换 TTS 引擎
     */
    fun switchEngine(packageName: String?) {
        ttsService?.switchEngine(packageName)
    }

    fun toggleShowSettings() {
        _readerUiState.value = _readerUiState.value.copy(
            showSettings = !_readerUiState.value.showSettings
        )
    }

    fun dismissTtsServerGuide() {
        _readerUiState.value = _readerUiState.value.copy(needsTtsServerGuide = false)
    }

    fun toggleKeepScreenOn(keep: Boolean) {
        _readerUiState.value = _readerUiState.value.copy(keepScreenOn = keep)
    }

    /**
     * 获取 TTS 诊断信息（用于调试对话框）
     */
    fun getDiagnostics(): String {
        return try {
            ttsService?.getDiagnostics() ?: "Service 还未连接，请等待..."
        } catch (e: Exception) {
            "获取诊断信息出错: ${e.message}"
        }
    }

    override fun onCleared() {
        // 保存阅读进度
        currentBook?.let { book ->
            viewModelScope.launch {
                repository.updateProgress(book.id, _ttsState.value.currentPosition)
            }
        }
        try {
            context.unbindService(serviceConnection)
        } catch (_: Exception) {}
        super.onCleared()
    }
}

data class ReaderUiState(
    val isLoading: Boolean = false,
    val bookTitle: String = "",
    val fullText: String = "",
    val errorMessage: String? = null,
    val showSettings: Boolean = false,
    val keepScreenOn: Boolean = false,
    val pendingBook: BookEntity? = null,
    val needsTtsServerGuide: Boolean = false
)
