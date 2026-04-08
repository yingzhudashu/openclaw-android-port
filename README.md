# OpenClaw Android

**AI 个人助手，完全在手机端侧运行。**

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg)](https://developer.android.com)
[![Architecture](https://img.shields.io/badge/Arch-arm64--v8a-blue.svg)]()

> 🤖 本项目由 [OpenClaw](https://openclaw.ai) AI 助手独立开发，人类负责需求和验收。

OpenClaw Android 将完整的 AI Agent 能力带到 Android 手机上。通过内嵌 Node.js Gateway，实现了与桌面端对齐的核心功能——包括多步工具调用、子代理系统、向量记忆、Skills 加载和浏览器控制。

## ✨ 核心特性

### 🧠 Agent 能力
- **SSE 流式对话** — 实时逐字输出，支持 thinking/reasoning 显示
- **28 个工具** — 文件操作、命令执行、网页搜索/抓取、浏览器控制、新闻摘要、技能管理、记忆搜索等
- **多步工具循环** — LLM 自主决策调用工具，最多 25 步自动推理
- **子代理系统** — 动态创建子代理执行独立任务（sessions_spawn/send/list/yield）
- **进程管理** — 后台执行命令 + 进程监控（process tool）
- **向量记忆** — embedding + cosine similarity 语义搜索
- **Skills 自动加载** — 扫描 skills/ 目录注入 system_prompt

### 🔧 多模型支持
- **5 家供应商** — 百炼(通义千问)、OpenAI、Anthropic、DeepSeek、SiliconFlow
- **模型 Fallback** — 主模型失败自动切换备用模型
- **聊天内快速切换** — 随时更换模型，无需进设置

### 📱 用户体验
- 深色模式（Material Design）
- 代码块复制 · Markdown 链接点击
- 消息搜索（实时高亮 + 自动滚动定位）
- 聊天导出 · 备份/恢复

### ⚙️ 系统能力
- Cron 定时任务 + Heartbeat 心跳
- Agent 模板（预设子代理角色）
- Gateway Watchdog 自动重启 + Foreground Service 常驻
- 配置保护（升级不丢失 API Key）
- WebViewBridge 浏览器控制

## 📥 快速开始

### 安装
1. 从 [Releases](https://github.com/yingzhudashu/openclaw-android-port/releases) 下载最新 APK
2. 安装到 Android 8.0+ 设备（仅支持 arm64）
3. 进入 **设置 → 模型供应商**，配置至少一个 LLM 供应商的 API Key
4. 回到聊天页面，开始对话

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
┌─────────────────────────────────────────────┐
│              Android App (Kotlin)            │
│                                             │
│  ChatFragment ─── MessageAdapter            │
│  SettingsFragment ─── CronFragment          │
│  StatusFragment ─── GatewayService          │
│       │                    │                │
│       ▼                    ▼                │
│  ApiClient (HTTP) ──► NodeRunner            │
│       │               (ProcessBuilder)      │
│       ▼                    │                │
│  ┌─────────────────────────▼───────────┐    │
│  │      Node.js Gateway (:18789)       │    │
│  │                                     │    │
│  │  ├── LLM API (multi-provider)       │    │
│  │  ├── Agent Loop (multi-step tools)  │    │
│  │  ├── Sub-Agent Engine               │    │
│  │  ├── Process Manager                │    │
│  │  ├── 28 Tools                       │    │
│  │  ├── Vector Memory                  │    │
│  │  ├── Skills Loader                  │    │
│  │  ├── Cron Manager                   │    │
│  │  └── Session Manager                │    │
│  └─────────────────────────────────────┘    │
│       │                                     │
│       ▼                                     │
│  WebViewBridge (:18790)                     │
└─────────────────────────────────────────────┘
        │
        ▼  HTTPS
   LLM Providers
```

详细项目结构见 [poc-app/README.md](poc-app/README.md)。

## 🔒 隐私与安全

- **零硬编码凭证** — 仓库中不包含任何 API Key 或 Token
- **本地存储** — 所有数据（会话、配置、备份）仅存储在设备本地
- **无遥测** — 不收集或传输任何使用数据
- **用户控制** — API Key 由用户输入，可随时清除
- **安全政策** — 详见 [SECURITY.md](SECURITY.md)

## 📖 文档

| 文档 | 说明 |
|------|------|
| [CHANGELOG.md](CHANGELOG.md) | 版本变更记录 |
| [CONTRIBUTING.md](CONTRIBUTING.md) | 贡献指南 |
| [SECURITY.md](SECURITY.md) | 安全政策与漏洞报告 |
| [poc-app/README.md](poc-app/README.md) | 详细架构与项目结构 |
| [poc-app/CHANGELOG.md](poc-app/CHANGELOG.md) | 详细版本日志 |

## 📄 License

MIT — 详见 [LICENSE](LICENSE)
