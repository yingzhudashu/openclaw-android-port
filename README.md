# OpenClaw Android

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Version](https://img.shields.io/github/v/release/yingzhudashu/openclaw-android-port?label=version)](https://github.com/yingzhudashu/openclaw-android-port/releases)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg)](https://developer.android.com)
[![Architecture](https://img.shields.io/badge/Arch-arm64--v8a-blue.svg)]()

**AI 个人助手，完全在手机端侧运行。**

OpenClaw Android 将完整的 AI Agent 能力带到 Android 手机上。通过内嵌 Node.js Gateway，实现了与桌面端对齐的核心功能——包括多步工具调用、子代理系统、向量记忆、Skills 自动加载、浏览器控制，以及设备控制 API（相机、GPS、通知读取）。

> 🤖 本项目由 [OpenClaw](https://openclaw.ai) AI 助手独立开发，人类负责需求定义和验收。

## ✨ 核心特性

### 🧠 Agent 智能体能力
- **SSE 流式对话** — 实时逐字输出，支持 thinking/reasoning 过程展示
- **28+ 工具调用** — 文件操作、命令执行、网页搜索/抓取、浏览器控制、新闻摘要、技能管理、记忆搜索等
- **多步工具循环** — LLM 自主决策调用工具，最多 25 步自动推理
- **子代理系统** — 动态创建子代理执行独立任务（sessions_spawn/send/list/yield）
- **进程管理** — 后台执行命令 + 进程监控（process tool）
- **向量记忆** — embedding + cosine similarity 语义搜索
- **Skills 自动加载** — 扫描 skills/ 目录注入 system_prompt

### 📷 设备控制 API（v1.3.0 新增）
- **相机拍照** — 通过 \/device/camera/snap\ 调用系统相机，返回 base64 图片
- **GPS 定位** — 通过 \/device/location\ 获取设备位置
- **通知读取** — 通过 \/device/notifications\ 读取最近通知
- 配套透明 Activity（CameraCaptureActivity）和照片缓冲区（PhotoStore）
- 本地 HTTP 服务器运行在 \127.0.0.1:18791\

### 🔐 权限管理
- 统一管理 5 类运行时权限：位置、相机、麦克风、通知、存储
- 首次启动自动请求缺失权限
- 设置页可查看权限状态并跳转系统设置

### 🔧 多模型支持
- **5 家供应商** — 百炼（通义千问）、OpenAI、Anthropic、DeepSeek、SiliconFlow
- **模型 Fallback** — 主模型失败自动切换备用模型
- **聊天内快速切换** — 随时更换模型，无需进设置

### 📱 用户体验
- 深色模式（Material Design）
- 代码块复制 · Markdown 链接点击
- 消息搜索（实时高亮 + 自动滚动定位）
- 聊天导出 · 备份/恢复 · 多语言（中/英）

### ⚙️ 系统能力
- Cron 定时任务（持久化存储）+ Heartbeat 心跳检查
- Agent 模板（预设子代理角色）
- Gateway Watchdog 自动重启 + Foreground Service 常驻
- 配置保护（升级/恢复不丢失 API Key）
- WebViewBridge 浏览器控制（端口 18790）

## 📥 快速开始

### 安装
1. 从 [Releases](https://github.com/yingzhudashu/openclaw-android-port/releases) 下载最新 APK
2. 安装到 Android 8.0+ 设备（仅支持 arm64）
3. 进入 **设置 → 模型供应商**，配置至少一个 LLM 供应商的 API Key
4. 首次启动时授予运行时权限（位置/相机/通知等）
5. 回到聊天页面，开始对话

### 从源码构建
\\\ash
git clone https://github.com/yingzhudashu/openclaw-android-port.git
cd openclaw-android-port/poc-app

# 需要 JDK 17 + Android SDK 34
./gradlew assembleDebug
\\\

APK 输出：\pp/build/outputs/apk/debug/app-debug.apk\

> **MIUI/HyperOS 用户**：标准 \db install\ 可能被拦截，使用 \install_miui.ps1\ 脚本安装。

## 🏗️ 架构

\\\
┌─────────────────────────────────────────────────────┐
│              Android App (Kotlin)                    │
│                                                     │
│  ChatFragment ──── MessageAdapter                   │
│  SettingsFragment ──── CronFragment                 │
│  StatusFragment ──── GatewayService                 │
│       │                    │                        │
│       ▼                    ▼                        │
│  ┌──────────┐    ┌──────────────────────┐           │
│  │ ApiClient│───▶│  NodeRunner           │           │
│  │ (HTTP)   │    │  (ProcessBuilder)     │           │
│  └──────────┘    └──────────────────────┘           │
│       │                    │                        │
│       ▼                    ▼                        │
│  ┌─────────────────────────────────────┐            │
│  │    Node.js Gateway (:18789)         │            │
│  │                                     │            │
│  │  ├── LLM API (multi-provider)       │            │
│  │  ├── Agent Loop (multi-step tools)  │            │
│  │  ├── Sub-Agent Engine               │            │
│  │  ├── Process Manager                │            │
│  │  ├── 28 Tools                       │            │
│  │  ├── Vector Memory                  │            │
│  │  ├── Skills Loader                  │            │
│  │  ├── Cron Manager (persistent)      │            │
│  │  └── Session Manager                │            │
│  └─────────────────────────────────────┘            │
│       │                                             │
│  ┌──────────────────┐    ┌──────────────────┐      │
│  │ WebViewBridge     │    │ DeviceControlApi  │      │
│  │ (:18790)         │    │ (:18791)          │      │
│  └──────────────────┘    └──────────────────┘      │
│                            │                       │
│                      ┌─────┴──────┐                │
│                      ▼            ▼                │
│                 Camera      Location               │
│                 Capture     + GPS                  │
└───────────────────────────────────────────────────┘
        │
        ▼  HTTPS
   LLM Providers
\\\

### 端口分配

| 端口 | 服务 | 说明 |
|------|------|------|
| 18789 | Gateway | AI Agent 核心服务 |
| 18790 | WebViewBridge | 浏览器控制桥接 |
| 18791 | DeviceControlApi | 设备控制 API |

## 📂 项目结构

\\\
openclaw-android-port/
├── poc-app/                    # Android 应用源码
│   ├── app/src/main/
│   │   ├── java/ai/openclaw/poc/
│   │   │   ├── MainActivity.kt
│   │   │   ├── ChatFragment.kt
│   │   │   ├── SettingsFragment.kt
│   │   │   ├── DeviceControlApi.kt    # v1.3.0 新增
│   │   │   ├── CameraCaptureActivity.kt
│   │   │   ├── PhotoStore.kt
│   │   │   ├── PermissionManager.kt
│   │   │   ├── ComprehensiveTest.kt
│   │   │   └── ...
│   │   ├── assets/openclaw-engine/
│   │   │   └── android-gateway.cjs   # 4000+ 行
│   │   └── jniLibs/arm64-v8a/
│   │       └── libnode.so
│   └── install_miui.ps1
├── engine/                     # 桌面端 Gateway 源码
├── native-libs/                # 预编译 Node.js 共享库
├── scripts/                    # 构建和部署脚本
├── README.md                   # 本文件
├── CHANGELOG.md                # 版本变更记录
├── CONTRIBUTING.md             # 贡献指南
├── SECURITY.md                 # 安全策略
├── CODE_OF_CONDUCT.md          # 行为准则
└── LICENSE                     # MIT 许可证
\\\

详细子项目文档见 [poc-app/README.md](poc-app/README.md)。

## 🔒 隐私与安全

- **零硬编码凭证** — 仓库中不包含任何 API Key 或 Token
- **本地存储** — 所有数据（会话、配置、备份）仅存储在设备本地
- **无遥测** — 不收集或传输任何使用数据
- **用户控制** — API Key 由用户输入，可随时清除
- **安全策略** — 详见 [SECURITY.md](SECURITY.md)
- **行为准则** — 详见 [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)

## 🗺️ 路线图

### 已完成
- [x] 基础聊天界面与多会话管理（v1.0.0）
- [x] SSE 流式对话 + 多步工具循环 + 28 工具（v1.1.0）
- [x] 子代理系统 + 进程管理（v1.2.0）
- [x] 设备控制 API + 权限管理 + 社区规范（v1.3.0）

### 规划中
- [ ] Release 构建（R8 混淆 + 签名）
- [ ] x86_64 架构支持（模拟器）
- [ ] F-Droid 上架

## 📖 文档

| 文档 | 说明 |
|------|------|
| [CHANGELOG.md](CHANGELOG.md) | 版本变更记录 |
| [CONTRIBUTING.md](CONTRIBUTING.md) | 贡献指南 |
| [SECURITY.md](SECURITY.md) | 安全策略与漏洞报告 |
| [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) | 社区行为准则 |
| [poc-app/README.md](poc-app/README.md) | 详细架构、技术栈、开发指南 |
| [poc-app/CHANGELOG.md](poc-app/CHANGELOG.md) | 详细版本日志 |

## 📄 License

MIT — 详见 [LICENSE](LICENSE)