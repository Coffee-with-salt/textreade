# 文本朗读器 (TextReader)

一个 Android 文本朗读 App，支持 TXT / DOCX 导入，系统 TTS 引擎朗读，多种朗读设置。

## 功能

- 📖 导入 TXT / DOCX 电子书
- 🔊 调用系统 TTS 引擎朗读（离线可用）
- ⚡ 语速调节 0.25x ~ 3.0x
- 🔁 循环朗读、定时停止
- 🌙 息屏继续朗读
- 📱 阅读进度自动保存

## 快速开始

### 下载 APK

前往 [Releases](https://github.com/Coffee-with-salt/textreade/releases) 页面下载最新版本的 APK，安装到手机即可。

### 从源码构建

#### 环境要求

- Android Studio Hedgehog (2023.1.1) 或更高
- JDK 17+
- Android SDK compileSdk 35+

#### 构建步骤

```bash
# 进入项目目录
cd TextReader

# 构建 Debug APK
./gradlew assembleDebug

# APK 位于 app/build/outputs/apk/debug/app-debug.apk
```

详细构建说明见 [BUILD.md](BUILD.md)。

## 引擎推荐

Android 系统本身不提供高质量的中文 TTS 语音，推荐安装 [TTS Server](https://github.com/jing332/tts-server-android)：

- 开源免费，离线可用
- 支持多种 TTS 引擎统一管理
- 提供高质量离线中文语音

详见 [TTS_ENGINE_GUIDE.md](TTS_ENGINE_GUIDE.md)。

## 项目结构

```
TextReader/
├── app/src/main/
│   ├── java/com/textreader/app/
│   │   ├── MainActivity.kt          # 入口 Activity
│   │   ├── service/TtsService.kt     # TTS 前台服务
│   │   ├── ui/screens/              # Compose UI
│   │   ├── viewmodel/               # ViewModel
│   │   ├── data/                    # Room 数据库
│   │   └── util/                    # 工具类
│   └── res/                         # 资源文件
├── build.gradle.kts                 # 项目级构建
└── gradle/libs.versions.toml        # 版本目录
```

## 技术栈

- **语言**: Kotlin 2.0.21
- **UI**: Jetpack Compose + Material 3
- **架构**: MVVM + Hilt DI
- **数据库**: Room
- **异步**: Coroutines + StateFlow

## 许可证

MIT
