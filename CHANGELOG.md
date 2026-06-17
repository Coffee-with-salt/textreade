# Changelog

本项目所有版本变更记录。

## [1.0.4] - 2026-06-16
### Changed
- **关键：去掉 `.debug` 后缀** — `applicationId` 改为 `com.textreader.app`
  - 原因：ColorOS 把 `.debug` 后缀应用识别为"未认证/调试应用"，被 `AppsFilter BLOCKED` 拦截
  - 解决：debug build 仍然用 `versionNameSuffix = "-debug"` 标记版本，但 applicationId 干净
- **添加 `BIND_TTS_ENGINE` 权限** — 显式声明调用 TTS 引擎的权限
- **添加 `<queries>` 块** — Android 11+ 包可见性要求，显式声明要查询 TTS_SERVICE
- **新版 `TtsEnvironment` 工具类** — 检测系统 TTS 引擎、检测应用是否被识别为"调试应用"、跳 TTS 设置/应用权限设置

### Added
- **TTS 启动失败引导对话框** — TTS 初始化失败时自动弹出
  - 显示具体错误信息
  - 列出已安装的所有 TTS 引擎（包名 + 应用名）
  - 提示当前应用是否被识别为调试应用
  - 三个按钮：[打开 TTS 设置] [应用权限] [关闭]
- **MainActivity 启动时检测 TTS 环境** — 把结果写入 logcat，方便诊断

### Build
- `versionCode`: 3 → 4
- `versionName`: "1.0.2" → "1.0.3-debug"
- 修改文件: `app/src/main/AndroidManifest.xml`, `app/build.gradle.kts`, `MainActivity.kt`, `TtsService.kt`, `ReaderScreen.kt`, 新增 `util/TtsEnvironment.kt`

## [1.0.3] - 2026-06-16
### Fixed
- **编译错误** — `getDefaultEngine`、`getEngines`、`it.name` 等方法未解析
  - 根本原因：这些是 `TextToSpeech` 的**实例方法**，不是静态方法，不能用 `TextToSpeech.getDefaultEngine()` 调用
  - 修复：改用 `tts.defaultEngine` 和 `tts.engines` 实例方法语法
  - 引擎信息在初始化成功后缓存到 `lastDefaultEngine` / `lastEngineNames`，避免诊断时调用失败
- **编译错误** — `tts?.speechRate` 未解析
  - 根本原因：TextToSpeech 没有 `getSpeechRate()` 方法，只有 `setSpeechRate()`
  - 修复：`getDiagnostics()` 改用内部状态 `_state.value.speechRate`
- **编译错误** — 调试对话框在错误的作用域（SettingsPanel 内部）
  - 修复：将 AlertDialog 块移到 `ReaderScreen` 函数中，正确访问 `showDebugDialog` 和 `debugText` 局部变量
- **编译错误** — `return@let` 不能在普通 for 块中使用
  - 修复：把 `try { ... tts ?: return@let ... }` 改成 `tts?.let { current -> ... }`

### Build
- 添加 `android.suppressUnsupportedCompileSdk=36` 到 `gradle.properties` 抑制警告
- `versionCode`: 3 → 3（无变化，但重新构建成功）
- `versionName`: "1.0.2" → 实际构建：1.0.2 (versionCode 3)
- 修改文件: `TtsService.kt`、`ReaderScreen.kt`、`gradle.properties`
- **APK 构建成功**: `app/build/outputs/apk/debug/app-debug.apk` (17.1 MB)

## [1.0.2] - 2026-06-16
### Fixed
- **TTS 引擎初始化持续失败** (status=-1 重试 5 次仍失败)
  - 显式传入默认引擎包名给 `TextToSpeech` 构造器，绕过 ColorOS 自动选择问题
  - `getEngines()` 和 `getDefaultEngine()` 改为全限定名 + try-catch，避免反射失败
  - `getDiagnostics()` 改用 `pm.getApplicationInfo(info.name, 0)` 解析引擎包名
  - `viewModel.getDiagnostics()` 加 try-catch 兜底，避免编译错误

### Added
- **TTS 调试对话框** — 顶部 AppBar 加 🔍 按钮，点击弹出诊断信息（引擎就绪状态、已安装引擎、当前 Locale、语速、SDK 版本等）

### Build
- `versionCode`: 2 → 3
- `versionName`: "1.0.1" → "1.0.2"
- 修改文件: `TtsService.kt`、`ReaderViewModel.kt`、`ReaderScreen.kt`、`app/build.gradle.kts`

## [1.0.1] - 2026-06-16
### Fixed
- **TTS 引擎绑定失败** (ColorOS/Android 16 兼容)
  - 新增 TTS 初始化重试机制（5 次 × 500ms）
  - 优化多语种回退逻辑：中文 → 英文 → 默认
  - 在 `onCreate` 中先调用 `startForeground()`，避免 TTS 初始化期间被系统杀死
  - 修复 `textChunks` 为空时 `currentChunkIndex` 计算崩溃
  - `speak()` 调用增加错误码检查，错误信息显示到通知栏
  - 关键路径增加 `Log.d` 便于排查
- **TTS 引擎未绑定** 导致 `speak failed: not bound to TTS engine`

### Build
- `versionCode`: 1 → 2
- `versionName`: "1.0.0" → "1.0.1"
- 修改文件: `app/build.gradle.kts`、`app/src/main/java/com/textreader/app/service/TtsService.kt`

## [1.0.0] - 2026-06-15
### Added
- 初始版本
- TXT 文件导入（自动识别 UTF-8/GBK 编码）
- DOCX 文件导入（使用内置 ZIP + XML 解析，无第三方库依赖）
- 系统 TTS 朗读
- 语速调节（0.25x ~ 4.0x）
- 循环朗读
- 定时停止（15~120分钟）
- 息屏继续朗读（WakeLock）
- 屏幕常亮可选
- 阅读进度自动保存
- 前台服务（通知栏不被杀死）
- 书架管理（Room 数据库）
- Jetpack Compose UI
- MVVM 架构 + Hilt DI
- Material 3 主题

### Technical
- Kotlin 2.0.21 + Jetpack Compose
- compileSdk 36 / minSdk 26
- AGP 8.7.0 / Gradle 8.9
- 不使用 Apache POI（采用内置 ZIP 解析，APK 体积减少 10MB）
