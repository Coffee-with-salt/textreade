# TTS 引擎使用指南

## 概述

TextReader 支持多种 TTS（文字转语音）引擎。App 会自动检测系统中已安装的引擎，按优先级排序，用户可自由切换。

## 引擎优先级

| 优先级 | 引擎 | 类型 | 说明 |
|--------|------|------|------|
| 1-2 | 微软离线 TTS | 离线 | 系统级离线中文语音，需要手动安装 |
| 3 | TTS Server | 离线 | 推荐安装，开源免费，管理多种 TTS 引擎 |
| 4 | Edge TTS | 在线 | Edge 浏览器附带的 TTS 引擎 |
| 100+ | 系统默认引擎 | - | 回退选项 |

## 推荐的 TTS Server

**TTS Server** 是一个开源免费的第三方 TTS 引擎管理工具：

- [GitHub 仓库](https://github.com/jing332/tts-server-android)
- [下载地址](https://github.com/jing332/tts-server-android/releases)

### 特点

- 支持多种 TTS 引擎统一管理
- 提供高质量离线中文语音
- 无需网络连接即可使用
- 自动设置系统默认 TTS 引擎

### 安装步骤

1. 下载并安装 TTS Server APK
2. 打开 TTS Server → 设置 → 启用 TTS 引擎
3. 回到 TextReader，在阅读器设置中切换引擎
4. 开始朗读

## 引擎检测逻辑

App 启动时自动检测：

1. 创建 `TextToSpeech` 实例 → 获取所有已安装引擎
2. 按 `ENGINE_PRIORITY` map 排序
3. 如果检测到微软引擎或 TTS Server → 直接使用
4. 如果都没有 → 弹出引导对话框推荐安装 TTS Server

## 用户操作流程

### 首次启动（无微软 TTS / TTS Server）

1. App 检测到没有好的引擎
2. 弹出「推荐安装离线语音引擎」对话框
3. 点击「去安装」→ 打开 TTS Server 下载页面
4. 安装后启用引擎 → 回到 App 在设置中切换

### 已安装引擎

1. 打开任意书籍进入阅读器
2. 点击右上角设置按钮
3. 在「朗读引擎」区域选择目标引擎
4. 切换后需重新播放生效

## 技术实现

### 核心类

- **`TtsEngineManager`** — 引擎管理器
  - `getPrioritizedEngineList()` — 按优先级获取可用引擎列表
  - `getRecommendedEngine()` — 获取推荐引擎包名
  - `needsTtsServerRecommendation()` — 检测是否需要推荐 TTS Server
  - `saveSelectedEngine()` / `getSelectedEngineFlow()` — 用户偏好持久化

- **`TtsService`** — TTS 前台服务
  - `switchEngine()` — 切换引擎并重建 TTS 实例
  - `collectEngineCandidates()` — 收集候选引擎列表
  - `loadUserEnginePreference()` — 加载用户引擎偏好

- **`ReaderScreen`** — UI 层
  - SettingsPanel 中的引擎选择 UI
  - 安装引导对话框

### 用户偏好存储

使用 DataStore 持久化用户选择的引擎包名，保存在 `tts_engine_prefs` 文件中。

## 本次改动清单（v1.0.4）

| 文件 | 改动内容 |
|------|---------|
| `util/TtsEngineManager.kt` | 新增 Edge TTS 优先级、`TTS_SERVER_INSTALL_URL` 常量、`needsTtsServerRecommendation()` 方法 |
| `service/TtsService.kt` | `TtsState` 新增 `needsTtsServerRecommendation` 字段；TTS 初始化成功后自动检测是否需要推荐 |
| `viewmodel/ReaderViewModel.kt` | `ReaderUiState` 新增 `needsTtsServerGuide` 字段 + `dismissTtsServerGuide()` 方法 |
| `ui/screens/ReaderScreen.kt` | 新增「推荐安装离线语音引擎」引导对话框 |

## 数据流

```
TtsService.onCreate()
  └─ TTS 初始化成功
     └─ TtsEngineManager.needsTtsServerRecommendation(context)
        └─ true → _state.value.copy(needsTtsServerRecommendation = true)
           └─ state.collect { ... }
              └─ ReaderViewModel (监听 _ttsState)
                 └─ _readerUiState.copy(needsTtsServerGuide = true)
                    └─ ReaderScreen (收集 uiState)
                       └─ showInstallGuideDialog = true
                          └─ 弹出引导对话框
```

## 引擎状态流转

1. **初始状态** — `needsTtsServerRecommendation = false`
2. **TTS 初始化完成** — 调用 `needsTtsServerRecommendation()` 检测
3. **需要推荐** — 设为 `true` → 同步到 ViewModel → UI 弹出对话框
4. **用户关闭/安装后回来** — 调用 `dismissTtsServerGuide()` 恢复 `false`
