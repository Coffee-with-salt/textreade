package com.textreader.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.compose.rememberNavController
import com.textreader.app.ui.navigation.AppNavGraph
import com.textreader.app.ui.theme.TextReaderTheme
import com.textreader.app.util.TtsEnvironment
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 通知权限，非必须 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 请求通知权限（Android 13+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // ★ 关键：检测 TTS 环境（仅记录日志，不打扰用户）
        checkTtsEnvironment()

        setContent {
            TextReaderTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    AppNavGraph(navController = navController)
                }
            }
        }
    }

    /**
     * 检查 TTS 环境并记录到日志
     * 帮助用户/开发者理解为什么 TTS 可能初始化失败
     */
    private fun checkTtsEnvironment() {
        try {
            val isDebug = TtsEnvironment.isDebugBuildSuspect(this)
            val engines = TtsEnvironment.listInstalledEngines(this)
            Log.d(TAG, "=== TTS 环境检测 ===")
            Log.d(TAG, "本应用是否被识别为'调试/测试'应用: $isDebug")
            Log.d(TAG, "packageName=${packageName}")
            Log.d(TAG, "已安装 TTS 引擎数量=${engines.size}")
            engines.forEachIndexed { i, e ->
                Log.d(TAG, "  [${i + 1}] ${e.label} (${e.packageName})")
            }
            if (isDebug) {
                Log.w(TAG, "⚠️ 本应用是 DEBUG 构建，可能被 ColorOS AppsFilter 拦截")
                Log.w(TAG, "   建议：发布 release 版本，或在 ColorOS 设置中手动加白")
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkTtsEnvironment failed: ${e.message}")
        }
    }
}
