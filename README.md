# OpenClaw Android

**AI 个人助手，完全在手机端侧运行。**

> 🤖 本项目由 [OpenClaw](https://openclaw.ai) AI 助手独立开发，人类负责需求和验收。

OpenClaw Android 将完整的 AI Agent 能力带到 Android 手机上。通过内嵌 Node.js Gateway，实现了与桌面端对齐的核心功能——包括多步工具调用、向量记忆、Skills 加载和浏览器控制。

## 核心特性

### 🧠 Agent 能力
- **SSE 流式对话** — 实时逐字输出，支持 thinking/reasoning 显示
- **22+ 工具** — web_search、web_fetch、exec、file ops、browser control、news_summary、skill management、memory search 等
- **多步工具循环** — LLM 自主决策调用工具，最多 25 步自动推理
- **向量记忆** — embedding + cosine similarity 语义搜索
- **Skills 自动加载** — 扫描 skills/ 目录注入 system_prompt

### 🔧 多模型支持
- **5 家供应商** — 百炼(通义千问)、OpenAI、Anthropic、DeepSeek、SiliconFlow
- **模型 Fallback** — 主模型失败自动切换备用模型
- **聊天内快速切换** — 随时更换模型，无需进设置

### 📱 用户体验
- 深色模式 · 代码块复制 · 链接点击
- 消息搜索（实时高亮 + 自动滚动定位）
- 聊天导出 · 备份/恢复

### ⚙️ 系统能力
- Cron 定时任务 + Heartbeat 心跳
- Agent 模板（预设子代理角色）
- Gateway Watchdog 自动重启 + Foreground Service 常驻
- 配置保护（升级不丢失 API Key）
- WebViewBridge 浏览器控制

## 快速开始

### 安装
1. 从 [Releases](https://github.com/yingzhudashu/openclaw-android-port/releases) 下载最新 APK
2. 安装到 Android 8.0+ 设备（仅支持 arm64）
3. 进入设置，配置至少一个 LLM 供应商的 API Key
4. 开始对话

### 从源码构建
```bash
git clone https://github.com/yingzhudashu/openclaw-android-port.git
cd openclaw-android-port/poc-app
# 需要 Android Studio JBR 17 + SDK 34
./gradlew assembleDebug
```
APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

## 架构

```
Android App (Kotlin)
    │
    ├── ChatFragment ── MessageAdapter
    ├── SettingsFragment ── CronFragment
    ├── StatusFragment ── GatewayService
    │                        │
    ▼                        ▼
ApiClient (HTTP) ──► NodeRunner (ProcessBuilder)
    │                        │
    ▼                        ▼
    Node.js Gateway (:18789)
    ├── LLM API (multi-provider)
    ├── Agent Loop (tool calls)
    ├── 22 Tools
    ├── Vector Memory
    ├── Skills Loader
    ├── Cron Manager
    └── Session Manager
```

详细架构和项目结构见 [poc-app/README.md](poc-app/README.md)。

## 隐私与安全

- **无硬编码 API Key** — 所有凭证由用户输入，仅存储在设备本地
- **本地存储** — 会话、配置、备份均在设备上
- **无遥测** — 不收集或传输任何使用数据

## 文档

- [CHANGELOG.md](CHANGELOG.md) — 版本变更记录
- [CONTRIBUTING.md](CONTRIBUTING.md) — 贡献指南
- [poc-app/README.md](poc-app/README.md) — 详细架构与项目结构

## License

MIT — 详见 [LICENSE](LICENSE)
