# OpenClaw Android

<p align="center">
  <strong>AI 个人助手，完全在手机端侧运行。</strong>
</p>

<p align="center">
  OpenClaw Android 将完整的 AI Agent 能力带到 Android 手机上。通过内嵌 Node.js Gateway，实现了与桌面端对齐的核心功能——包括多步工具调用、向量记忆、Skills 自动加载和浏览器控制。
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="License: MIT"></a>
  <a href="https://github.com/yingzhudashu/openclaw-android-port/releases"><img src="https://img.shields.io/badge/version-1.3.0-green.svg" alt="Version: 1.3.0"></a>
  <a href="https://kotlinlang.org/"><img src="https://img.shields.io/badge/Kotlin-2.0-purple.svg" alt="Kotlin"></a>
  <a href="https://nodejs.org/"><img src="https://img.shields.io/badge/Node.js-embedded-brightgreen.svg" alt="Node.js embedded"></a>
</p>

> 🤖 本项目由 OpenClaw AI 助手独立开发，人类负责需求定义和验收。

---

## ✨ 核心特性

### 🧠 Agent 智能体能力

| 功能 | 描述 |
|------|------|
| **SSE 流式对话** | 实时逐字输出，支持 thinking/reasoning 过程展示 |
| **28+ 工具调用** | web_search、web_fetch、exec、file 操作、browser 控制、news_summary、skill 管理等 |
| **多步工具循环** | LLM 自主决策调用工具，最多 25 步自动推理 |
| **子代理系统** | 动态创建子代理执行独立任务（sessions_spawn/send/list/yield） |
| **进程管理** | 后台命令执行 + 进程监控（process tool） |
| **向量记忆** | embedding + cosine similarity 语义搜索 |
| **Skills 自动加载** | 扫描 skills/ 目录自动注入 system_prompt |

### 🔧 多模型支持

支持 **5 家供应商**，可在设置中配置并随时切换：

- **百炼（通义千问）** — 阿里云 DashScope API
- **OpenAI** — gpt-4o 系列
- **Anthropic** — Claude 系列
- **DeepSeek** — deepseek-chat
- **SiliconFlow** — 多模型聚合

**模型 Fallback**：主模型失败自动切换备用模型，确保服务高可用。

### 📱 用户体验

- 🌙 **深色模式** — 完整的 Material Design 暗色主题
- 📋 **代码块复制** — 一键复制代码片段
- 🔗 **链接点击** — Markdown 链接和裸链接均可跳转
- 🔍 **消息搜索** — 实时高亮 + 自动滚动定位
- 📤 **聊天导出** — 导出为 Markdown 文件
- 💾 **备份/恢复** — 一键备份所有配置、记忆、会话
- 🌐 **多语言** — 中文 / English 实时切换

### ⚙️ 系统能力

| 能力 | 说明 |
|------|------|
| **Cron 定时任务** | WorkManager 调度 + 通知推送，支持持久化存储 |
| **Heartbeat 心跳** | 定时健康检查 + 自动恢复 |
| **Agent 模板** | 预设子代理角色（翻译官、代码助手等） |
| **Gateway Watchdog** | Node.js 崩溃自动重启 |
| **Foreground Service** | 常驻运行不被系统杀死 |
| **配置保护** | 升级/恢复不丢失 API Key |
| **浏览器控制** | WebViewBridge（端口 18790） |
| **设备控制 API** | 相机拍照、GPS 定位、通知读取（端口 18791） |
| **运行时权限** | 位置/相机/通知/存储权限统一管理 |

### 📷 图片与多模态

- 支持图片上传与 AI 分析
- 智能 token 估算（图片固定 ~800 tokens/张，对齐 Vision API 标准）
- 消息上下文自动截断，确保图片不被误丢弃

---

## 🏗️ 架构

```
┌─────────────────────────────────────────────────────────┐
│              Android App (Kotlin)                        │
│                                                         │
│  ChatFragment ──── MessageAdapter                       │
│  SettingsFragment ──── SettingDetailFragment            │
│  StatusFragment ──── CronFragment                       │
│       │                    │                            │
│       ▼                    ▼                            │
│  ┌──────────┐    ┌──────────────────────┐               │
│  │ ApiClient│───▶│  NodeRunner           │               │
│  │ (HTTP)   │    │  (ProcessBuilder)     │               │
│  └──────────┘    └──────────────────────┘               │
│       │                    │                            │
│       ▼                    ▼                            │
│  ┌─────────────────────────────────────────┐            │
│  │    Node.js Gateway (:18789)             │            │
│  │                                         │            │
│  │  android-gateway.cjs (4000+ lines)      │            │
│  │  ├── LLM API (multi-provider)           │            │
│  │  ├── Agent Loop (tool calls)            │            │
│  │  ├── Sub-Agent Engine                   │            │
│  │  ├── Process Manager                    │            │
│  │  ├── 28 Tools                           │            │
│  │  ├── Vector Memory                      │            │
│  │  ├── Skills Loader                      │            │
│  │  ├── Cron Manager (persistent)          │            │
│  │  └── Session Manager                    │            │
│  └─────────────────────────────────────────┘            │
│       │                                                 │
│  ┌──────────────────┐    ┌──────────────────┐          │
│  │ WebViewBridge     │    │ DeviceControlApi  │          │
│  │ (:18790)         │    │ (:18791)          │          │
│  └──────────────────┘    └──────────────────┘          │
│       │                    │                            │
│       │              ┌─────┴──────┐                     │
│       │              ▼            ▼                     │
│       │         Camera      Location                   │
│       │         Capture     + GPS                      │
│       │                                               │
└───────┴───────────────────────────────────────────────┘
        │
        ▼  HTTPS
┌───────────────────┐
│  LLM Providers    │
│  百炼 / OpenAI /  │
│  Anthropic / ...  │
└───────────────────┘
```

### 端口分配

| 端口 | 服务 | 说明 |
|------|------|------|
| 18789 | Gateway | AI Agent 核心服务 |
| 18790 | WebViewBridge | 浏览器控制桥接 |
| 18791 | DeviceControlApi | 设备功能 API |

---

## 📂 项目结构

```
poc-app/
├── app/src/main/
│   ├── java/ai/openclaw/poc/
│   │   ├── MainActivity.kt            # 主 Activity + Tab 导航 + 权限请求
│   │   ├── ChatFragment.kt            # 聊天界面 + SSE 流式处理 + 健康检查
│   │   ├── SettingsFragment.kt        # 设置页（模型/供应商/备份/权限状态）
│   │   ├── SettingDetailFragment.kt   # Markdown 编辑器（MEMORY.md 编辑）
│   │   ├── StatusFragment.kt          # Gateway 状态监控
│   │   ├── CronFragment.kt            # 定时任务管理
│   │   ├── CronManager.kt             # Cron 数据模型
│   │   ├── CronWorker.kt              # WorkManager 定时执行
│   │   ├── HeartbeatWorker.kt         # 心跳检查
│   │   ├── GatewayService.kt          # Foreground Service
│   │   ├── NodeRunner.kt              # Node.js 进程管理
│   │   ├── ApiClient.kt               # HTTP 客户端
│   │   ├── MessageAdapter.kt          # 消息列表适配器
│   │   ├── Session.kt                 # 会话数据模型
│   │   ├── WebViewBridge.kt           # 浏览器桥接（18790 端口）
│   │   ├── DeviceControlApi.kt        # 设备控制 API（18791 端口）
│   │   ├── CameraCaptureActivity.kt   # 透明拍照 Activity
│   │   ├── PhotoStore.kt              # 照片缓冲区
│   │   ├── PermissionManager.kt       # 运行时权限管理
│   │   ├── ComprehensiveTest.kt       # 集成测试套件
│   │   ├── ViewPagerAdapter.kt        # Tab 页适配器
│   │   ├── LocaleHelper.kt            # 多语言支持
│   │   └── adapter/                   # 设置页适配器
│   ├── assets/openclaw-engine/
│   │   ├── android-gateway.cjs        # Gateway 核心（4000+ 行）
│   │   ├── openclaw.json              # 运行时配置
│   │   ├── SOUL.md                    # AI 人格设定
│   │   └── MEMORY.md                  # 记忆索引
│   ├── jniLibs/arm64-v8a/
│   │   └── libnode.so                 # Node.js 运行时共享库
│   └── res/                           # 布局/字符串/主题/XML 配置
├── install_miui.ps1                   # MIUI/HyperOS 安装脚本
├── README.md                          # 本文件
├── CHANGELOG.md                       # 版本变更日志
├── CONTRIBUTING.md                    # 贡献指南
├── SECURITY.md                        # 安全策略
├── CODE_OF_CONDUCT.md                 # 行为准则
├── LICENSE                            # MIT 许可证
├── .gitignore                         # Git 忽略规则
└── build.gradle.kts                   # 项目级构建配置
```

---

## 🚀 快速开始

### 前置条件

| 要求 | 版本 |
|------|------|
| Android Studio | 最新版本（JBR 17+） |
| Android SDK | compileSdk 34 |
| JDK | 17+ |
| Node.js 共享库 | libnode.so (arm64-v8a) |

### 编译 Debug APK

```powershell
# Windows PowerShell
$env:JAVA_HOME = "D:\Android\Android Studio\jbr"
cd D:\AIhub\openclaw-android-port\poc-app
.\gradlew assembleDebug
```

APK 输出路径：`app/build/outputs/apk/debug/app-debug.apk`

### 安装到设备

```powershell
# 标准安装
adb install app\build\outputs\apk\debug\app-debug.apk

# MIUI/HyperOS（会被系统拦截，需用 session install）
.\install_miui.ps1 -ApkPath "app\build\outputs\apk\debug\app-debug.apk"
```

### 首次使用

1. **安装 APK** 并启动应用
2. **配置 API Key**：进入 设置 → 模型供应商，填写至少一个供应商的 API Key
3. **授权运行时权限**：首次启动会请求位置/相机/通知等权限（可在设置页查看和管理）
4. **开始对话**：回到聊天页面，发送消息即可

---

## 📋 技术栈

### Android 层

- **语言**: Kotlin 2.0
- **minSdk**: 26 (Android 8.0)
- **targetSdk**: 34
- **UI 框架**: XML Layout + Material Design Components
- **架构**: Fragment + ViewPager2 + TabLayout
- **后台任务**: WorkManager + Foreground Service
- **进程管理**: ProcessBuilder（启动 Node.js）

### Gateway 层

- **运行时**: Node.js（嵌入式 libnode.so）
- **模块格式**: CommonJS（`.cjs`）
- **HTTP 框架**: Node.js 内置 `http` 模块（零外部依赖）
- **LLM 客户端**: Node.js 内置 `https` 模块
- **向量记忆**: cosine similarity（纯 JS 实现）

### 关键设计决策

| 决策 | 原因 |
|------|------|
| XML Layout（非 Compose） | 减少依赖，更简单直接 |
| arm64-v8a only | 匹配目标硬件，减小 APK 体积 |
| useLegacyPackaging = true | .so 不压缩，可直接加载 |
| Process-based Node.js | 非 JNI 嵌入，进程隔离更稳定 |
| 纯内置模块（零依赖） | 无外部 npm 包，避免兼容性问题 |

---

## 📖 设备控制 API

v1.3.0 新增的设备功能接口，运行在 `127.0.0.1:18791`，仅本地可访问：

| 端点 | 方法 | 说明 |
|------|------|------|
| `/device/camera/snap` | POST | 调用系统相机拍照，返回 base64 图片数据 |
| `/device/location` | GET | 获取当前 GPS 位置（经纬度） |
| `/device/notifications` | GET | 读取最近通知列表 |

> ⚠️ 这些 API 仅供 Gateway 内部工具调用，不建议外部直接访问。

---

## 🔧 开发指南

### 添加新工具

在 `android-gateway.cjs` 的 `TOOLS` 对象中注册：

```javascript
TOOLS: {
  my_new_tool: {
    description: '工具描述',
    parameters: { /* JSON Schema */ },
    execute: async (params) => {
      // 实现逻辑
      return { result: 'success' };
    }
  }
}
```

### 添加新权限

1. 在 `AndroidManifest.xml` 声明权限
2. 在 `PermissionManager.kt` 添加检查和请求逻辑
3. 在 `strings.xml` 添加权限描述文案

### 调试 Gateway

```bash
# 查看 Gateway 日志
adb logcat | grep -E "NodeRunner|Gateway"

# 查看完整应用日志
adb logcat | grep "OpenClaw"
```

### 运行测试

App 内置 Comprehensive Test 套件：
- 打开 App → 设置 → 运行测试
- 自动测试 Gateway API、Browser Bridge、Device Control API
- 输出结构化报告

---

## 🗺️ 路线图

### 已完成
- [x] 基础聊天界面与多会话管理
- [x] Node.js Gateway 内嵌运行
- [x] SSE 流式对话 + thinking 显示
- [x] 多步工具循环 + 28+ 工具
- [x] 子代理系统
- [x] 向量记忆
- [x] 多模型供应商 + Fallback
- [x] Cron 定时任务（持久化）
- [x] 设备控制 API（相机/位置/通知）
- [x] 运行时权限管理
- [x] 多模态图片识别修复

### 规划中
- [ ] Release 构建（R8 混淆 + 签名）
- [ ] x86_64 架构支持（模拟器）
- [ ] 语音输入功能回归
- [ ] 消息加密传输
- [ ] F-Droid 上架

---

## 📄 License

本项目采用 [MIT License](LICENSE) 开源。

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！请先阅读 [贡献指南](CONTRIBUTING.md)。

## ⚠️ 安全

发现安全问题请参考 [安全策略](SECURITY.md) 进行报告。
