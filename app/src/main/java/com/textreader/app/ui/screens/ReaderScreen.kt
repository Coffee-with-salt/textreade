package com.textreader.app.ui.screens

import android.view.WindowManager
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.textreader.app.util.TtsEngineManager
import com.textreader.app.viewmodel.ReaderViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookId: Long,
    onBack: () -> Unit,
    viewModel: ReaderViewModel = hiltViewModel()
) {
    val uiState by viewModel.readerUiState.collectAsState()
    val ttsState by viewModel.ttsState.collectAsState()
    val view = LocalView.current
    val context = LocalContext.current
    var showDebugDialog by remember { mutableStateOf(false) }
    var debugText by remember { mutableStateOf("") }
    var showTtsGuideDialog by remember { mutableStateOf(false) }
    var installedEngines by remember { mutableStateOf<List<com.textreader.app.util.TtsEnvironment.EngineInfo>>(emptyList()) }
    var isDebugSuspect by remember { mutableStateOf(false) }
    var selectedEnginePackageName by remember { mutableStateOf<String?>(null) }
    var availableTtsEngines by remember { mutableStateOf<List<TtsEngineInfo>>(emptyList()) }
    var showEngineSwitchToast by remember { mutableStateOf(false) }
    var toastMessage by remember { mutableStateOf("") }
    var showInstallGuideDialog by remember { mutableStateOf(false) }

    // 加载书籍
    LaunchedEffect(bookId) {
        viewModel.loadBook(bookId)
    }

    // 打开调试对话框时自动获取诊断信息
    LaunchedEffect(showDebugDialog) {
        if (showDebugDialog) {
            debugText = viewModel.getDiagnostics()
        }
    }

    // 检测 TTS 环境（仅在书籍加载后做一次）
    LaunchedEffect(Unit) {
        installedEngines = com.textreader.app.util.TtsEnvironment.listInstalledEngines(context)
        isDebugSuspect = com.textreader.app.util.TtsEnvironment.isDebugBuildSuspect(context)
        // 加载引擎列表和推荐引擎
        val engineList = TtsEngineManager.getPrioritizedEngineList(context)
        val recommended = TtsEngineManager.getRecommendedEngine(context)
        availableTtsEngines = engineList.map { info ->
            TtsEngineInfo(info.packageName, info.label, info.packageName == recommended)
        }
        selectedEnginePackageName = recommended
    }

    // 显示切换引擎 Toast
    var snackVisible by remember { mutableStateOf(false) }
    LaunchedEffect(showEngineSwitchToast) {
        if (showEngineSwitchToast) {
            snackVisible = true
            kotlinx.coroutines.delay(2500L)
            snackVisible = false
            showEngineSwitchToast = false
        }
    }

    // 当 TTS 初始化失败时，弹出引导对话框
    LaunchedEffect(ttsState.errorMessage) {
        val err = ttsState.errorMessage ?: return@LaunchedEffect
        if (err.contains("TTS", ignoreCase = true) || err.contains("status=")) {
            showTtsGuideDialog = true
        }
    }

    // 当需要推荐 TTS Server 时，弹出安装引导
    LaunchedEffect(uiState.needsTtsServerGuide) {
        showInstallGuideDialog = uiState.needsTtsServerGuide
    }

    // 控制屏幕常亮
    LaunchedEffect(uiState.keepScreenOn) {
        val activity = view.context as? android.app.Activity
        if (uiState.keepScreenOn) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        uiState.bookTitle.ifEmpty { "加载中..." },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Medium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.stop()
                        onBack()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 调试按钮
                    IconButton(onClick = { showDebugDialog = true }) {
                        Icon(Icons.Default.BugReport, contentDescription = "调试")
                    }
                    // 设置按钮
                    IconButton(onClick = { viewModel.toggleShowSettings() }) {
                        Icon(Icons.Default.Tune, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                uiState.errorMessage != null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            modifier = Modifier.size(60.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(uiState.errorMessage ?: "加载失败")
                    }
                }
                else -> {
                    Column(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // 文本预览区域（显示当前朗读位置附近的文字）
                        TextPreviewArea(
                            fullText = uiState.fullText,
                            currentPosition = ttsState.currentPosition,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        )

                        // 进度条
                        ProgressBar(
                            progress = ttsState.progress,
                            currentPos = ttsState.currentPosition,
                            totalChars = ttsState.totalChars,
                            onSeek = { viewModel.seekTo(it) }
                        )

                        // 播放控制区
                        PlayerControls(
                            isPlaying = ttsState.isPlaying,
                            isLooping = ttsState.isLooping,
                            speechRate = ttsState.speechRate,
                            timerMinutes = ttsState.timerMinutes,
                            keepScreenOn = uiState.keepScreenOn,
                            onPlayPause = { viewModel.play() },
                            onStop = { viewModel.stop() },
                            onSkipForward = { viewModel.skipForward() },
                            onSkipBackward = { viewModel.skipBackward() },
                            onLoopToggle = { viewModel.setLooping(!ttsState.isLooping) },
                            onScreenOnToggle = { viewModel.toggleKeepScreenOn(!uiState.keepScreenOn) }
                        )
                    }
                }
            }

            // 设置面板
            if (uiState.showSettings) {
                SettingsPanel(
                    speechRate = ttsState.speechRate,
                    timerMinutes = ttsState.timerMinutes,
                    isLooping = ttsState.isLooping,
                    keepScreenOn = uiState.keepScreenOn,
                    currentEngine = selectedEnginePackageName,
                    availableEngines = availableTtsEngines,
                    onSpeechRateChange = { viewModel.setSpeechRate(it) },
                    onTimerSet = { viewModel.setTimer(it) },
                    onLoopChange = { viewModel.setLooping(it) },
                    onScreenOnChange = { viewModel.toggleKeepScreenOn(it) },
                    onSwitchEngine = { pkg ->
                        selectedEnginePackageName = pkg
                        viewModel.switchEngine(pkg)
                        toastMessage = if (pkg != null) "已切换到: ${availableTtsEngines.find { it.packageName == pkg }?.label ?: pkg}，需重启播放"
                        else "已切换回系统推荐引擎，需重启播放"
                        showEngineSwitchToast = true
                    },
                    onDismiss = { viewModel.toggleShowSettings() },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }

            // Toast 提示
            if (snackVisible && toastMessage.isNotEmpty()) {
                androidx.compose.material3.SnackbarHost(
                    hostState = remember { SnackbarHostState() },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }

    // ===== 调试对话框 =====
    if (showDebugDialog) {
        AlertDialog(
            onDismissRequest = { showDebugDialog = false },
            title = { Text("TTS 诊断信息") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = debugText.ifEmpty { "正在获取诊断信息..." },
                        fontSize = 12.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    debugText = viewModel.getDiagnostics()
                }) { Text("刷新") }
            },
            dismissButton = {
                TextButton(onClick = { showDebugDialog = false }) { Text("关闭") }
            }
        )
    }

    // ===== TTS 启动失败引导对话框 =====
    if (showTtsGuideDialog) {
        AlertDialog(
            onDismissRequest = { showTtsGuideDialog = false },
            title = { Text("⚠️ 朗读引擎初始化失败") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "错误信息：${ttsState.errorMessage ?: "未知错误"}",
                        fontSize = 12.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("可能原因:", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))

                    if (isDebugSuspect) {
                        Text("• 本应用是 DEBUG 构建，ColorOS 厂商策略可能拦截")
                        Text("  (已去掉 .debug 后缀，请重新安装)")
                    }

                    Text("• 系统 TTS 引擎被 ColorOS 安全策略拦截")
                    Text("• 系统中没有可用的 TTS 引擎")
                    Spacer(modifier = Modifier.height(12.dp))

                    Text("已检测到 ${installedEngines.size} 个 TTS 引擎：",
                        fontWeight = FontWeight.Bold)
                    if (installedEngines.isEmpty()) {
                        Text("  （无）", fontSize = 12.sp)
                    } else {
                        installedEngines.forEach { e ->
                            Text("  • ${e.label} (${e.packageName})", fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text("请按以下步骤操作:", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("1. 打开「TTS 设置」启用任一引擎")
                    Text("2. 打开「应用权限」允许 TTS 访问")
                    Text("3. 返回本 App 重试")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    com.textreader.app.util.TtsEnvironment.openTtsSettings(context)
                }) { Text("打开 TTS 设置") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        com.textreader.app.util.TtsEnvironment.openAppPermissionSettings(context)
                    }) { Text("应用权限") }
                    TextButton(onClick = { showTtsGuideDialog = false }) { Text("关闭") }
                }
            }
        )
    }

    // ===== TTS Server 安装引导对话框 =====
    if (showInstallGuideDialog) {
        AlertDialog(
            onDismissRequest = {
                viewModel.dismissTtsServerGuide()
                showInstallGuideDialog = false
            },
            title = { Text("推荐安装离线语音引擎") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "当前未检测到微软离线 TTS 引擎或 TTS Server。"
                        + "为了获得更好的中文语音体验，推荐使用：",
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "TTS Server — 开源免费的第三方 TTS 引擎管理工具",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Text("• 支持多种 TTS 引擎统一管理", fontSize = 13.sp)
                    Text("• 提供高质量离线中文语音", fontSize = 13.sp)
                    Text("• 无需网络连接即可使用", fontSize = 13.sp)
                    Text("• 自动设置系统默认 TTS 引擎", fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("安装后请：", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("1. 打开 TTS Server → 设置 → 启用 TTS 引擎", fontSize = 13.sp)
                    Text("2. 回到本 App，在设置中切换引擎", fontSize = 13.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(TtsEngineManager.TTS_SERVER_INSTALL_URL)
                    )
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    viewModel.dismissTtsServerGuide()
                    showInstallGuideDialog = false
                }) { Text("去安装") }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.dismissTtsServerGuide()
                    showInstallGuideDialog = false
                }) { Text("稍后再说") }
            }
        )
    }
}

/**
 * 文本预览区域 - 显示当前朗读位置周围的文字
 */
@Composable
fun TextPreviewArea(
    fullText: String,
    currentPosition: Int,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    // 取当前位置周围的文字展示
    val displayText = remember(fullText, currentPosition) {
        if (fullText.isEmpty()) return@remember ""
        val start = maxOf(0, currentPosition - 200)
        val end = minOf(fullText.length, currentPosition + 800)
        fullText.substring(start, end)
    }

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
        ) {
            if (fullText.isEmpty()) {
                Text(
                    "等待朗读...",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            } else {
                // 已读部分（较暗）
                if (currentPosition > 200) {
                    Text(
                        "...",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
                        fontSize = 14.sp
                    )
                }
                Text(
                    text = displayText,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        lineHeight = 28.sp,
                        fontSize = 17.sp
                    ),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }

        // 进度指示
        if (fullText.isNotEmpty()) {
            Text(
                text = "${currentPosition}/${fullText.length}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
                modifier = Modifier.align(Alignment.TopEnd)
            )
        }
    }
}

/**
 * 进度条
 */
@Composable
fun ProgressBar(
    progress: Float,
    currentPos: Int,
    totalChars: Int,
    onSeek: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "${currentPos / 1000}k",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            Text(
                "${(progress * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "${totalChars / 1000}k",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
        Slider(
            value = progress,
            onValueChange = { newProgress ->
                val newPos = (newProgress * totalChars).toInt()
                onSeek(newPos)
            },
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

/**
 * 播放控制区
 */
@Composable
fun PlayerControls(
    isPlaying: Boolean,
    isLooping: Boolean,
    speechRate: Float,
    timerMinutes: Int,
    keepScreenOn: Boolean,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onSkipForward: () -> Unit,
    onSkipBackward: () -> Unit,
    onLoopToggle: () -> Unit,
    onScreenOnToggle: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 4.dp,
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 状态行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 语速显示
                AssistChip(
                    onClick = {},
                    label = { Text("${speechRate}x") },
                    leadingIcon = {
                        Icon(Icons.Default.Speed, null, modifier = Modifier.size(16.dp))
                    }
                )

                // 循环状态
                FilterChip(
                    selected = isLooping,
                    onClick = onLoopToggle,
                    label = { Text("循环") },
                    leadingIcon = {
                        Icon(Icons.Default.Repeat, null, modifier = Modifier.size(16.dp))
                    }
                )

                // 定时剩余
                if (timerMinutes > 0) {
                    AssistChip(
                        onClick = {},
                        label = { Text("${timerMinutes}分钟") },
                        leadingIcon = {
                            Icon(Icons.Default.Timer, null, modifier = Modifier.size(16.dp))
                        }
                    )
                }

                // 常亮控制
                FilterChip(
                    selected = keepScreenOn,
                    onClick = onScreenOnToggle,
                    label = { Text("常亮") },
                    leadingIcon = {
                        Icon(
                            if (keepScreenOn) Icons.Default.LightMode else Icons.Outlined.LightMode,
                            null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 主控制按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 后退
                IconButton(
                    onClick = onSkipBackward,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Default.Replay10,
                        contentDescription = "后退",
                        modifier = Modifier.size(32.dp)
                    )
                }

                // 停止
                IconButton(
                    onClick = onStop,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "停止",
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }

                // 播放/暂停（大按钮）
                FloatingActionButton(
                    onClick = onPlayPause,
                    modifier = Modifier.size(64.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        modifier = Modifier.size(36.dp)
                    )
                }

                // 前进
                IconButton(
                    onClick = onSkipForward,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Default.Forward10,
                        contentDescription = "前进",
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/**
 * 设置面板（底部滑出）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPanel(
    speechRate: Float,
    timerMinutes: Int,
    isLooping: Boolean,
    keepScreenOn: Boolean,
    currentEngine: String?,
    availableEngines: List<TtsEngineInfo>,
    onSpeechRateChange: (Float) -> Unit,
    onTimerSet: (Int) -> Unit,
    onLoopChange: (Boolean) -> Unit,
    onScreenOnChange: (Boolean) -> Unit,
    onSwitchEngine: (String?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState()
    val timerOptions = listOf(0, 15, 30, 45, 60, 90, 120)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                "阅读设置",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            // 语速调节
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("语速", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${speechRate}x",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }

                Slider(
                    value = speechRate,
                    onValueChange = {
                        // 步进 0.25
                        val snapped = (it / 0.25f).roundToInt() * 0.25f
                        onSpeechRateChange(snapped)
                    },
                    valueRange = 0.25f..3.0f,
                    steps = 10,
                    modifier = Modifier.fillMaxWidth()
                )

                // 快速语速按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { rate ->
                        FilterChip(
                            selected = speechRate == rate,
                            onClick = { onSpeechRateChange(rate) },
                            label = { Text("${rate}x", fontSize = 11.sp) }
                        )
                    }
                }
            }

            Divider()

            // 循环开关
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("循环朗读", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "读完后从头开始",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Switch(checked = isLooping, onCheckedChange = onLoopChange)
            }

            Divider()

            // 息屏朗读（保持CPU唤醒）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("息屏朗读", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "关屏后继续朗读（不常亮）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                // 息屏朗读是默认支持的（WakeLock），这里只是状态提示
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            // 常亮开关
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("屏幕常亮", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "朗读时保持屏幕不熄灭",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Switch(checked = keepScreenOn, onCheckedChange = onScreenOnChange)
            }

            Divider()

            // 定时停止
            Column {
                Text("定时停止", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    timerOptions.forEach { minutes ->
                        FilterChip(
                            selected = timerMinutes == minutes,
                            onClick = { onTimerSet(minutes) },
                            label = {
                                Text(
                                    if (minutes == 0) "不限时" else "${minutes}分钟",
                                    fontSize = 12.sp
                                )
                            }
                        )
                    }
                }
            }

            Divider()

            // TTS 引擎选择
            Column {
                Text("朗读引擎", style = MaterialTheme.typography.titleSmall)
                Text(
                    "当前: ${currentEngine ?: "系统推荐"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (availableEngines.isEmpty()) {
                    Text("未检测到 TTS 引擎", style = MaterialTheme.typography.bodySmall)
                } else {
                    availableEngines.forEach { engine ->
                        val isSelected = engine.packageName == currentEngine
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                )
                                .clickable { onSwitchEngine(engine.packageName) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { onSwitchEngine(engine.packageName) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    engine.label,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    engine.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    maxLines = 1
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * TTS 引擎信息（用于 UI 展示）
 */
data class TtsEngineInfo(
    val packageName: String,
    val label: String,
    val isRecommended: Boolean = false
)
