# 文本朗读器 (TextReader) - 构建说明

## 环境要求

| 工具 | 版本要求 |
|------|---------|
| Android Studio | Hedgehog (2023.1.1) 或更高 |
| JDK | 17 或更高 |
| Android SDK | compileSdk 35, minSdk 26 |
| Gradle | 8.7（自动下载） |

---

## 方法一：Android Studio（推荐新手）

### 步骤

1. **打开项目**
   - 启动 Android Studio
   - `File → Open` → 选择 `TextReader` 文件夹
   - 等待 Gradle 同步（首次需要下载依赖，约 5-10 分钟）

2. **配置 SDK**
   - 如提示 SDK 缺失：`Tools → SDK Manager` → 安装 API 35

3. **构建 Debug APK**
   - 菜单：`Build → Build Bundle(s) / APK(s) → Build APK(s)`
   - 等待构建完成
   - 点击右下角 `locate` 按钮找到 APK 文件
   - 路径：`app/build/outputs/apk/debug/app-debug.apk`

4. **构建 Release APK（可分发）**
   - `Build → Generate Signed Bundle / APK`
   - 选择 APK
   - 创建或选择 keystore（签名文件）
   - 填写密钥信息 → Next → 选择 Release → Finish
   - 路径：`app/build/outputs/apk/release/app-release.apk`

---

## 方法二：命令行构建

### Windows（PowerShell / CMD）

```powershell
# 进入项目目录
cd C:\D\ankexi\read\TextReader

# 构建 Debug APK（无需签名，可直接安装测试）
.\gradlew assembleDebug

# APK 输出路径
# app\build\outputs\apk\debug\app-debug.apk
```

```powershell
# 构建 Release APK（已配置使用 debug 签名，可直接安装）
.\gradlew assembleRelease

# APK 输出路径
# app\build\outputs\apk\release\app-release.apk
```

### macOS / Linux

```bash
chmod +x gradlew
./gradlew assembleDebug
```

---

## 安装到手机

### 方法 A：直接传输
1. 将 APK 文件复制到手机
2. 用文件管理器找到 APK
3. 点击安装（需要在设置里开启"允许未知来源"）

### 方法 B：ADB 安装（手机连接电脑）
```bash
# 确认设备已连接
adb devices

# 安装 APK
adb install app\build\outputs\apk\debug\app-debug.apk
```

---

## 常见问题

### Q: Gradle 下载很慢
在 `gradle.properties` 中添加国内镜像：
```properties
# 阿里云镜像
systemProp.https.proxyHost=mirrors.aliyun.com
```

或者在 `settings.gradle.kts` 中替换仓库地址：
```kotlin
repositories {
    maven { url = uri("https://maven.aliyun.com/repository/public") }
    maven { url = uri("https://maven.aliyun.com/repository/google") }
    mavenCentral()
    google()
}
```

### Q: POI 库冲突导致构建失败
已在 `build.gradle.kts` 中配置了 `packaging excludes`，如仍报错请检查 `proguard-rules.pro`。

### Q: 找不到 `mipmap` 图标资源
需要在 `res/mipmap-*` 目录中放置图标文件，或在 manifest 中暂时将 `android:icon` 改为 `@android:drawable/sym_def_app_icon`。

---

## 功能说明

| 功能 | 说明 |
|------|------|
| 导入 TXT | 自动检测 UTF-8 / GBK 编码 |
| 导入 DOCX | 使用 Apache POI 解析段落 |
| TTS 朗读 | 调用系统 TTS 引擎（无需联网） |
| 语速调节 | 0.25x ~ 3.0x，步进 0.25 |
| 循环朗读 | 读完自动从头开始 |
| 定时停止 | 15/30/45/60/90/120 分钟 |
| 息屏朗读 | 系统关屏后 TTS 继续（WakeLock） |
| 屏幕常亮 | 阅读时保持屏幕亮着 |
| 进度保存 | 自动记录上次阅读位置 |
