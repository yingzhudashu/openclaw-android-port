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
- **相机拍照** — 通过 `/device/camera/snap` 调用系统相机，返回 base64 图片
- **GPS 定位** — 通过 `/device/location` 获取设备位置
- **通知读取** — 通过 `/device/notifications` 读取最近通知
- 配套透明 Activity（CameraCaptureActivity）和照片缓冲区（PhotoStore）
- 本地 HTTP 服务器运行在 `127.0.0.1:18791`

### 🔊 语音朗读（v1.4.0 新增）
- **TTS 朗读** — 点击消息旁的 🔊 图标，使用 Android 原生 TextToSpeech 引擎朗读
- 支持中文/英文自动语音切换
- 底部控制栏可停止朗读，状态栏显示当前状态

### 🖼️ 图片/PDF 查看器（v1.4.0 新增）
- **全屏图片查看器** — PhotoView 实现缩放、滑动、双击放大、长按保存
- **PDF 查看器** — Android PdfRenderer 实现翻页、缩放、页面指示器

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
```bash
git clone https://github.com/yingzhudashu/openclaw-android-port.git
cd openclaw-android-port/poc-app

# 需要 JDK 17 + Android SDK 34
./gradlew assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

> **MIUI/HyperOS 用户**：标准 `adb install` 可能被拦截，使用 `install_miui.ps1` 脚本安装。

## 🏗️ 架构

```
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
```

### 端口分配

| 端口 | 服务 | 说明 |
|------|------|------|
| 18789 | Gateway | AI Agent 核心服务 |
| 18790 | WebViewBridge | 浏览器控制桥接 |
| 18791 | DeviceControlApi | 设备控制 API |

## 📂 项目结构

```
openclaw-android-port/
├── poc-app/                    # 📱 Android 应用源码（主要开发目录）
│   ├── app/src/main/
│   │   ├── java/ai/openclaw/poc/
│   │   │   ├── MainActivity.kt          # 入口 + Tab 导航
│   │   │   ├── ChatFragment.kt          # 聊天界面 + TTS + 图片/PDF 查看
│   │   │   ├── SettingsFragment.kt      # 设置页（模型/Tavily/权限等）
│   │   │   ├── StatusFragment.kt        # 系统状态面板
│   │   │   ├── CronFragment.kt          # 定时任务管理
│   │   │   ├── NodeRunner.kt            # Node.js 进程管理
│   │   │   ├── GatewayService.kt        # Foreground Service
│   │   │   ├── ApiClient.kt             # HTTP 客户端
│   │   │   ├── DeviceControlApi.kt      # 设备控制 API（v1.3.0）
│   │   │   ├── CameraCaptureActivity.kt # 透明拍照 Activity
│   │   │   ├── PhotoStore.kt            # 照片缓冲区
│   │   │   ├── PermissionManager.kt     # 运行时权限管理
│   │   │   ├── ImageViewerActivity.kt   # 全屏图片查看器（v1.4.0）
│   │   │   ├── PdfViewerActivity.kt     # PDF 查看器（v1.4.0）
│   │   │   └── TtsManager.kt            # TTS 语音朗读管理（v1.4.0）
│   │   ├── assets/openclaw-engine/
│   │   │   └── android-gateway.cjs      # 4000+ 行 Gateway 核心
│   │   └── jniLibs/arm64-v8a/
│   │       └── libnode.so               # 预编译 Node.js 共享库
│   └── install_miui.ps1                 # MIUI 安装脚本
│
├── engine/                     # 🖥️ 桌面端 Gateway 源码（TypeScript）
│   ├── android-engine-poc.ts   # PoC 版入口
│   ├── android-entry.ts        # 完整版入口
│   ├── android-entry-real.ts   # 生产环境入口
│   ├── minimal-gateway.ts      # 最小化 Gateway
│   ├── bridges/                # 浏览器控制桥接
│   └── stubs/                  # 平台兼容占位（node-pty/playwright/sharp）
│
├── native-libs/                # 📦 预编译 Node.js 共享库
│   ├── arm64-v8a/libnode.so    # 主要目标架构（手机）
│   ├── armeabi-v7a/libnode.so  # 32 位兼容
│   ├── x86_64/libnode.so       # 模拟器支持
│   └── nmrn-extract/           # nodejs-mobile-react-native 提取
│
├── dist/                       # 📤 构建产物
│   ├── android-engine-poc.cjs  # PoC 版 Gateway
│   ├── android-engine.mjs      # 完整版 Gateway
│   └── android-full/           # 完整 Node.js 运行环境
│
├── app-shell/                  # 🐚 Android App 壳层源码
│   └── src/main/java/ai/openclaw/app/engine/
│       ├── EngineManager.kt     # 引擎管理器
│       ├── LocalGatewayClient.kt # 本地 Gateway 客户端
│       └── NodeEngineService.kt  # Node 引擎服务
│
├── scripts/                    # 🛠️ 构建和部署脚本
│
├── data/                       # 📊 运行时数据目录（已 .gitignore）
│   ├── config/                 # 配置文件
│   ├── memory/                 # 向量记忆数据
│   ├── sessions/               # 会话历史
│   └── workspace/              # 工作区文件
│
├── android-data/               # 📊 Android assets 数据目录（已 .gitignore）
│
├── README.md                   # 本文件
├── CHANGELOG.md                # 版本变更记录
├── CONTRIBUTING.md             # 贡献指南
├── SECURITY.md                 # 安全策略
├── CODE_OF_CONDUCT.md          # 行为准则
└── LICENSE                     # MIT 许可证
```

### 目录说明

| 目录 | 用途 | 是否提交 Git |
|------|------|-------------|
| `poc-app/` | **主要开发目录**，Android 应用源码 | ✅ 是 |
| `engine/` | 桌面端 Gateway 源码（TypeScript），供 poc-app/assets 中的 .cjs 参考 | ✅ 是 |
| `native-libs/` | 预编译 Node.js 共享库（从 nodejs-mobile 提取），体积大 | ❌ 已 .gitignore |
| `dist/` | TypeScript 编译产物（.cjs/.mjs），由 engine/ 生成 | ❌ 已 .gitignore |
| `app-shell/` | Android App 壳层，包含 EngineManager 等核心类 | ✅ 是 |
| `scripts/` | 构建和部署脚本 | ✅ 是 |
| `data/` | 运行时数据（配置/记忆/会话），设备运行时生成 | ❌ 已 .gitignore |
| `android-data/` | Android assets 数据目录，App 首次启动时初始化 | ❌ 已 .gitignore |

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
- [x] TTS 语音朗读 + 图片/PDF 查看器 + Tavily 设置（v1.4.0）
- [x] Release 构建（R8 混淆 + 签名 + 资源压缩）
- [x] APK 体积优化（release 仅 arm64-v8a，33.9 MB → 17.68 MB，↓48%）
- [x] Debug 双架构支持（+x86_64，模拟器开发）

### 规划中
- [ ] 消息加密传输
- [ ] F-Droid 上架
- [ ] 动态 feature module（按需下载 .so）

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
