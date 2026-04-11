# 开发指南

## 环境搭建

### 前置条件

| 要求 | 版本 |
|------|------|
| Android Studio | 最新稳定版（JBR 17+） |
| Android SDK | compileSdk 34 |
| JDK | 17+ |
| ADB | 已安装并加入 PATH |

### 克隆与编译

```powershell
# Windows PowerShell
git clone https://github.com/yingzhudashu/openclaw-android-port.git
cd openclaw-android-port/poc-app

# 设置 JDK 路径（根据实际情况修改）
$env:JAVA_HOME = "D:\Android\Android Studio\jbr"

# 编译 Debug APK
.\gradlew assembleDebug
```

### APK 输出路径

| 构建类型 | 输出路径 | 架构 |
|---------|---------|------|
| Debug | `app/build/outputs/apk/debug/app-debug.apk` | arm64-v8a + x86_64 |
| Release | `app/build/outputs/apk/release/app-release.apk` | arm64-v8a only |

### 安装到设备

```powershell
# 标准安装
adb install app\build\outputs\apk\debug\app-debug.apk

# 覆盖安装
adb install -r app\build\outputs\apk\release\app-release.apk

# MIUI/HyperOS（会被系统拦截，需用 session install）
.\install_miui.ps1 -ApkPath "app\build\outputs\apk\release\app-release.apk"
```

## 调试

### 查看日志

```bash
# Gateway 日志（Node.js 输出）
adb logcat | grep -E "NodeRunner|Gateway"

# 应用日志
adb logcat | grep "OpenClaw"

# 只看错误
adb logcat *:E | grep -E "NodeRunner|Gateway|OpenClaw"
```

### 调试技巧

- **Gateway 启动失败**：检查 `adb logcat | grep NodeRunner` 查看启动日志
- **聊天无响应**：确认 Gateway 进程在运行 `adb shell ps | grep openclaw`
- **设置页加载慢**：检查 Gateway 是否正在处理大量 Skills 或 Memory

### WebView 调试

Gateway 内置的 WebView 可通过 Chrome DevTools 远程调试：
```
chrome://inspect
```

## 代码结构

### 关键目录

```
poc-app/app/src/main/
├── java/ai/openclaw/poc/    # Kotlin 源码
│   ├── GatewayApi.kt        # 集中式 HTTP 客户端
│   ├── GatewayClient.kt     # SSE 流式通信
│   ├── MarkdownRenderer.kt  # Markdown 渲染
│   ├── ChatFragment.kt      # 聊天界面
│   ├── SettingsFragment.kt  # 设置页
│   └── ...
├── assets/openclaw-engine/  # 运行时 assets
│   ├── android-gateway.cjs  # Gateway 核心
│   └── openclaw.json        # 默认配置
└── jniLibs/arm64-v8a/       # 预编译 .so
    └── libnode.so
```

### 添加新功能

**添加 Gateway API 方法**：在 `GatewayApi.kt` 中注册，然后在 SettingsFragment 中调用。

**添加设备控制 API**：在 `DeviceControlApi.kt` 中添加路由，并在 `AndroidManifest.xml` 声明所需权限。

**添加工具**：在 `android-gateway.cjs` 的 `TOOLS` 对象中注册。

## 常见问题

### Q: 编译时报 Kotlin 版本不匹配
确保 `build.gradle.kts` 中的 Kotlin 插件版本与 Android Studio 内置版本兼容。

### Q: Release APK 无法在模拟器运行
Release 构建仅打包 arm64-v8a。模拟器开发请使用 Debug APK（包含 x86_64）。

### Q: MIUI 安装被拦截
使用 `install_miui.ps1` 脚本，通过 `adb session install` 绕过系统拦截。

### Q: Gateway 启动后闪退
检查 `adb logcat | grep NodeRunner`，常见原因：
- Node.js 共享库损坏
- 存储空间不足
- 权限被拒绝

### Q: 配置丢失
用户数据存储在应用沙盒内，卸载 App 会清除所有数据。使用设置页的「备份」功能定期备份。
