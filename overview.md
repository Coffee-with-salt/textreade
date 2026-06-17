# 文本朗读器 Android 应用 — 完成报告

## 应用概述

**应用名称**：文本朗读器 (TextReader)  
**包名**：com.textreader.app  
**最低安卓版本**：Android 8.0 (API 26)  
**目标安卓版本**：Android 15 (API 35)

---

## 架构选择：Kotlin + Jetpack Compose + MVVM + Hilt

这是目前 Google 官方推荐的最快开发方案：
- **Jetpack Compose**：现代声明式 UI，无需 XML 布局
- **MVVM**：ViewModel + StateFlow，单向数据流
- **Hilt**：依赖注入，零配置
- **Room**：SQLite ORM，书籍信息持久化
- **Coroutines**：异步操作

---

## 已实现功能

| 功能 | 实现方式 |
|------|---------|
| ✅ 导入 TXT | 自动检测 UTF-8/GBK/GB2312 编码 |
| ✅ 导入 DOCX | Apache POI 解析段落和表格 |
| ✅ 文本提取阅读 | 保存到应用内部存储，全文显示 |
| ✅ TTS 朗读 | 调用系统 Android TTS 引擎 |
| ✅ 语速调节 | 0.25x ~ 3.0x，快速切换按钮 |
| ✅ 循环朗读 | 读完自动从头再来 |
| ✅ 定时停止 | 15/30/45/60/90/120 分钟 |
| ✅ 息屏朗读 | PARTIAL_WAKE_LOCK 保持 CPU 运行 |
| ✅ 屏幕常亮 | FLAG_KEEP_SCREEN_ON 按需开启 |
| ✅ 进度记忆 | 自动保存上次阅读位置 |
| ✅ 前台服务 | 通知栏控制，后台不被杀死 |

---

## 项目文件结构

```
TextReader/
├── app/src/main/
│   ├── AndroidManifest.xml          # 权限、Activity、Service 声明
│   ├── java/com/textreader/app/
│   │   ├── MainActivity.kt          # 入口 Activity
│   │   ├── TextReaderApp.kt         # Application (Hilt)
│   │   ├── data/
│   │   │   ├── db/                  # Room 数据库 (Entity, Dao, Database)
│   │   │   └── repository/          # FileParser + BookRepository
│   │   ├── di/                      # Hilt 依赖注入模块
│   │   ├── service/
│   │   │   └── TtsService.kt        # 前台 TTS 服务（核心）
│   │   ├── ui/
│   │   │   ├── navigation/          # Compose 导航图
│   │   │   ├── screens/             # 书架界面 + 阅读器界面
│   │   │   └── theme/               # Material3 主题
│   │   └── viewmodel/               # BookshelfViewModel + ReaderViewModel
│   └── res/                         # 资源文件
├── build.gradle.kts                 # 项目级构建
├── app/build.gradle.kts             # 应用级构建
├── gradle/libs.versions.toml        # 版本目录
├── BUILD.md                         # 构建说明文档
└── build_apk.bat                    # Windows 一键构建脚本
```

---

## 如何构建 APK

### 最简单方式：Android Studio
1. 用 Android Studio 打开 `TextReader` 文件夹
2. 等待 Gradle 同步（首次约5分钟）
3. `Build → Build APK(s)` → 完成

### 命令行方式（需配置好 JAVA_HOME）
```bash
cd TextReader
gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

详见 `BUILD.md`

---

## 注意事项

1. **首次启动**：需要授予通知权限（Android 13+）用于前台服务通知
2. **TTS 引擎**：首次使用可能需要在系统设置中下载语音包
3. **DOC 旧格式**：需添加 `poi-scratchpad` 依赖；目前 DOCX 格式完全支持
4. **图标资源**：需要在 `res/mipmap-*` 目录添加 `ic_launcher.png` 图标才能构建

---

## 依赖项大小说明

Apache POI（解析 DOCX）比较大（约 8MB），已配置 ProGuard 混淆和 shrinkResources 压缩，Release APK 预计约 12-18MB。
