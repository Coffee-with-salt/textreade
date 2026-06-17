package com.textreader.app.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log

/**
 * TTS 环境检测器
 * 检测 ColorOS / 厂商策略对 TTS 的拦截，给用户明确的引导
 */
object TtsEnvironment {

    private const val TAG = "TtsEnvironment"

    /**
     * 列出所有系统已安装的 TTS 引擎
     */
    fun listInstalledEngines(context: Context): List<EngineInfo> {
        val result = mutableListOf<EngineInfo>()
        var probe: TextToSpeech? = null
        try {
            probe = TextToSpeech(context) { /* no-op */ }
            val engines = probe.engines
            val pm = context.packageManager
            for (e in engines) {
                val label = try {
                    val appInfo = pm.getApplicationInfo(e.name, 0)
                    pm.getApplicationLabel(appInfo).toString()
                } catch (ex: Exception) {
                    e.name
                }
                result.add(EngineInfo(e.name, label, e.icon))
            }
        } catch (e: Exception) {
            Log.e(TAG, "listInstalledEngines failed: ${e.message}")
        } finally {
            try { probe?.stop(); probe?.shutdown() } catch (_: Exception) {}
        }
        return result
    }

    /**
     * 检查当前应用是否被系统识别为"未认证/调试应用"
     * （ColorOS 会对 .debug 后缀应用做特殊拦截）
     */
    fun isDebugBuildSuspect(context: Context): Boolean {
        val ai = try {
            context.packageManager.getApplicationInfo(context.packageName, 0)
        } catch (e: Exception) {
            return false
        }
        // 有以下 flag 之一 → 系统可能视其为测试应用
        val isDebuggable = (ai.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val isTestOnly = (ai.flags and android.content.pm.ApplicationInfo.FLAG_TEST_ONLY) != 0
        return isDebuggable || isTestOnly
    }

    /**
     * 检查是否能直接跳转到 TTS 设置
     */
    fun canOpenTtsSettings(context: Context): Boolean {
        val intent = Intent("com.android.settings.TTS_SETTINGS")
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "cannot open TTS settings directly: ${e.message}")
            false
        }
    }

    /**
     * 打开系统 TTS 设置
     */
    fun openTtsSettings(context: Context): Boolean {
        // 尝试多种 intent
        val intents = listOf(
            Intent("com.android.settings.TTS_SETTINGS"),
            Intent().apply {
                setClassName(
                    "com.android.settings",
                    "com.android.settings.Settings\$TextToSpeechSettingsActivity"
                )
            },
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS) // 兜底
        )
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return true
            } catch (e: Exception) {
                // 继续尝试下一个
            }
        }
        return false
    }

    /**
     * 获取 OPPO/ColorOS 应用的特殊权限设置 Intent
     * 路径：设置 → 应用管理 → [App] → 权限 → 文字转语音
     */
    fun openAppPermissionSettings(context: Context): Boolean {
        val intents: List<Intent> = listOf<Intent>()
        // 标准应用详情页
        val detailIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(detailIntent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检查运行时是否已获得 POST_NOTIFICATIONS 权限
     */
    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat_checkSelfPermission(
            context,
            "android.permission.POST_NOTIFICATIONS"
        )
    }

    private fun ContextCompat_checkSelfPermission(context: Context, permission: String): Boolean {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    data class EngineInfo(
        val packageName: String,
        val label: String,
        val iconRes: Int
    )
}
