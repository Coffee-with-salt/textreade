package com.textreader.app.util

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * TTS 引擎管理器
 * - 优先选择微软离线语音引擎
 * - 自动 fallback 到其他引擎
 * - 用户偏好通过 DataStore 持久化
 */
object TtsEngineManager {

    private const val TAG = "TtsEngineManager"

    private val Context.ttsDataStore by preferencesDataStore(name = "tts_engine_prefs")

    private val SELECTED_ENGINE_KEY = stringPreferencesKey("selected_engine")

    /**
     * 引擎优先级（数字越小优先级越高）
     */
    private val ENGINE_PRIORITY = mapOf(
        // 微软离线 TTS（如果用户安装了）
        "com.microsoft.cortana.speech" to 1,
        "com.microsoft.speech" to 2,
        // TTS Server（推荐安装，开源免费，离线中文语音）
        "com.github.jing332.tts_server_android" to 3,
        // Edge TTS（Edge 浏览器附带的 TTS 引擎）
        "com.microsoft.azure.eds.tts" to 4,
    )

    /**
     * TTS Server 下载地址
     */
    const val TTS_SERVER_INSTALL_URL =
        "https://github.com/jing332/tts-server-android/releases"

    /**
     * 保存用户选择的 TTS 引擎包名（null = 用推荐引擎）
     */
    suspend fun saveSelectedEngine(context: Context, packageName: String?) {
        context.ttsDataStore.edit { prefs ->
            prefs[SELECTED_ENGINE_KEY] = packageName ?: ""
        }
        Log.d(TAG, "saveSelectedEngine: $packageName")
    }

    /**
     * 读取用户选择的引擎（Flow，用于 Compose 观察）
     */
    fun getSelectedEngineFlow(context: Context): Flow<String?> {
        return context.ttsDataStore.data.map { prefs ->
            val value = prefs[SELECTED_ENGINE_KEY]
            if (value.isNullOrEmpty()) null else value
        }
    }

    /**
     * 获取按优先级排序的可用引擎列表
     */
    fun getPrioritizedEngineList(context: Context): List<EngineInfo> {
        val result = mutableListOf<EngineInfo>()
        var probe: TextToSpeech? = null
        try {
            probe = TextToSpeech(context) { }
            val pm = context.packageManager
            for (e in probe.engines) {
                val label = try {
                    val appInfo = pm.getApplicationInfo(e.name, 0)
                    pm.getApplicationLabel(appInfo).toString()
                } catch (_: Exception) {
                    e.name
                }
                result.add(EngineInfo(e.name, label))
            }
        } catch (e: Exception) {
            Log.e(TAG, "getPrioritizedEngineList failed: ${e.message}")
        } finally {
            try { probe?.stop(); probe?.shutdown() } catch (_: Exception) {}
        }

        // 按优先级排序
        result.sortWith { a, b ->
            val prioA = ENGINE_PRIORITY[a.packageName] ?: 100
            val prioB = ENGINE_PRIORITY[b.packageName] ?: 100
            prioA.compareTo(prioB)
        }

        return result
    }

    /**
     * 获取推荐的默认引擎（用户未选择时的默认值）
     * 按优先级取第一个可用的引擎
     */
    fun getRecommendedEngine(context: Context): String? {
        val prioritized = getPrioritizedEngineList(context)
        return prioritized.firstOrNull { info ->
            info.packageName in ENGINE_PRIORITY
        }?.packageName
    }

    /**
     * 检查某个引擎是否已安装
     */
    fun isEngineAvailable(context: Context, packageName: String): Boolean {
        var probe: TextToSpeech? = null
        try {
            probe = TextToSpeech(context) { }
            return probe.engines.any { it.name == packageName }
        } catch (_: Exception) {
            return false
        } finally {
            try { probe?.stop(); probe?.shutdown() } catch (_: Exception) {}
        }
    }

    /**
     * 是否需要引导安装 TTS Server
     * 当没有任何微软系引擎（离线或云端）且没有 TTS Server 时返回 true
     */
    fun needsTtsServerRecommendation(context: Context): Boolean {
        var probe: TextToSpeech? = null
        try {
            probe = TextToSpeech(context) { }
            val engines = probe.engines
            val hasMicrosoftEngine = engines.any { e ->
                e.name.contains("microsoft", ignoreCase = true)
            }
            val hasTtsServer = engines.any {
                it.name == "com.github.jing332.tts_server_android"
            }
            return !hasMicrosoftEngine && !hasTtsServer
        } catch (_: Exception) {
            return true  // 连系统 TTS 都没有，更需要引导
        } finally {
            try { probe?.stop(); probe?.shutdown() } catch (_: Exception) {}
        }
    }

    data class EngineInfo(
        val packageName: String,
        val label: String
    )
}
