@echo off
chcp 65001 >nul
echo ====================================
echo  文本朗读器 APK 一键构建脚本
echo ====================================
echo.

cd /d "%~dp0"

echo [1/3] 检查 Gradle Wrapper...
if not exist "gradlew.bat" (
    echo 错误：找不到 gradlew.bat，请确保在项目根目录运行此脚本
    pause
    exit /b 1
)

echo [2/3] 开始构建 Debug APK...
call gradlew.bat assembleDebug --stacktrace

if %errorlevel% neq 0 (
    echo.
    echo 构建失败！请查看上方错误信息
    pause
    exit /b 1
)

echo.
echo [3/3] 构建成功！
echo.
echo APK 位置：app\build\outputs\apk\debug\app-debug.apk
echo.

set APK_PATH=%~dp0app\build\outputs\apk\debug\app-debug.apk
if exist "%APK_PATH%" (
    echo 正在打开 APK 所在文件夹...
    explorer /select,"%APK_PATH%"
) else (
    echo 警告：APK 文件不在预期位置，请手动查找
)

pause
