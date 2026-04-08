# OpenClaw Android

**AI 个人助手，完全在手机端侧运行。**

OpenClaw Android 将完整的 AI Agent 能力带到 Android 手机上。通过内嵌 Node.js Gateway，实现了与桌面端对齐的核心功能——包括多步工具调用、向量记忆、Skills 加载和浏览器控制。

> 🤖 本项目由 OpenClaw AI 助手独立开发，人类负责需求和验收。

## 核心特性

### 🧠 Agent 能力
- **SSE 流式对话** — 实时逐字输出，支持 thinking/reasoning 显示
- **28 个工具** — web_search、web_fetch、exec、file_read/write/edit/delete、browser_navigate/eval/click/type、news_summary、skill_search/install 等
- **多步工具循环** — LLM 自主决策调用工具，最多 25 步自动推理
- **子代理系统** — 动态创建子代理执行独立任务（sessions_spawn/send/list/yield）
- **进程管理** — 后台执行命令 + 进程监控（process tool）
- **向量记忆** — embedding + cosine similarity 语义搜索
- **Skills 自动加载** — 扫描 skills/ 目录注入 system_prompt

### 🔧 多模型支持
- **5 家供应商** — 百炼(通义千问)、OpenAI、Anthropic、DeepSeek、SiliconFlow
- **模型 Fallback** — 主模型失败自动切换备用模型
- **聊天内快速切换** — 随时更换模型，无需进设置
- **Token 管理** — 自动估算和截断上下文

### 📱 用户体验
- **深色模式** — 完整的 Material Design 暗色主题
- **代码块复制** — 一键复制代码片段
- **链接点击** — Markdown 链接和裸链接均可跳转
- **消息搜索** — 实时高亮 + 自动滚动定位
- **聊天导出** — 导出为 Markdown 文件
- **备份/恢复** — 一键备份所有配置、记忆、会话

### ⚙️ 系统能力
- **Cron 定时任务** — WorkManager 调度 + 通知推送
- **Heartbeat 心跳** — 定时健康检查
- **Agent 模板** — 预设子代理角色（翻译官、代码助手等）
- **Gateway Watchdog** — Node.js 崩溃自动重启
- **Foreground Service** — 常驻运行不被系统杀死
- **配置保护** — 升级/恢复不丢失 API Key
- **浏览器控制** — WebViewBridge（端口 18790）

## 架构

```
┌─────────────────────────────────────────────┐
│              Android App (Kotlin)            │
│                                             │
│  ChatFragment ─── MessageAdapter            │
│  SettingsFragment ─── CronFragment          │
│  StatusFragment ─── GatewayService          │
│       │                    │                │
│       ▼                    ▼                │
│  ┌─────────┐    ┌──────────────────┐        │
│  │ApiClient│───▶│  NodeRunner      │        │
│  │ (HTTP)  │    │  (ProcessBuilder)│        │
│  └─────────┘    └──────────────────┘        │
│       │                    │                │
│       ▼                    ▼                │
│  ┌─────────────────────────────────┐        │
│  │    Node.js Gateway (:18789)     │        │
│  │                                 │        │
│  │  android-gateway.cjs            │        │
│  │  ├── LLM API (multi-provider)   │        │
│  │  ├── Agent Loop (tool calls)    │        │
│  │  ├── Sub-Agent Engine               │    │
│  │  ├── Process Manager                │    │
│  │  ├── 28 Tools                   │        │
│  │  ├── Vector Memory              │        │
│  │  ├── Skills Loader              │        │
│  │  ├── Cron Manager               │        │
│  │  └── Session Manager            │        │
│  └─────────────────────────────────┘        │
│       │                                     │
│       ▼                                     │
│  ┌──────────────────┐                       │
│  │ WebViewBridge     │                       │
│  │ (:18790)          │                       │
│  └──────────────────┘                       │
└─────────────────────────────────────────────┘
        │
        ▼  HTTPS
┌───────────────┐
│ LLM Providers │
│ (百炼/OpenAI/ │
│  Anthropic/..)│
└───────────────┘
```

## 项目结构

```
poc-app/
├── app/src/main/
│   ├── java/ai/openclaw/poc/
│   │   ├── MainActivity.kt          # 主 Activity + Tab 导航
│   │   ├── ChatFragment.kt          # 聊天界面 + SSE 流式处理
│   │   ├── SettingsFragment.kt      # 设置页（模型/供应商/备份）
│   │   ├── SettingDetailFragment.kt  # Markdown 编辑器
│   │   ├── StatusFragment.kt        # Gateway 状态监控
│   │   ├── CronFragment.kt          # 定时任务管理
│   │   ├── CronManager.kt           # Cron 数据模型
│   │   ├── CronWorker.kt            # WorkManager 定时执行
│   │   ├── HeartbeatWorker.kt       # 心跳检查
│   │   ├── GatewayService.kt        # Foreground Service
│   │   ├── NodeRunner.kt            # Node.js 进程管理
│   │   ├── ApiClient.kt             # HTTP 客户端
│   │   ├── MessageAdapter.kt        # 消息列表适配器
│   │   ├── Session.kt               # 会话数据模型
│   │   ├── WebViewBridge.kt         # 浏览器桥接
│   │   ├── ViewPagerAdapter.kt      # Tab 页适配器
│   │   ├── LocaleHelper.kt          # 多语言支持
│   │   └── adapter/                  # 设置页适配器
│   ├── assets/openclaw-engine/
│   │   ├── android-gateway.cjs       # Gateway 核心（3700+ 行）
│   │   ├── openclaw.json             # 运行时配置
│   │   ├── SOUL.md                   # AI 人格设定
│   │   └── MEMORY.md                 # 记忆索引
│   ├── jniLibs/arm64-v8a/
│   │   └── libnode.so                # Node.js 运行时
│   └── res/                          # 布局/字符串/主题
├── install_miui.ps1                  # MIUI 安装脚本
├── build.gradle.kts
└── README.md
```

## 构建

### 前置条件
- Android Studio (JBR 17)
- Android SDK 34
- `libnode.so` (arm64-v8a Node.js 共享库)

### 编译
```powershell
$env:JAVA_HOME = "D:\Android\Android Studio\jbr"
cd D:\AIhub\openclaw-android-port\poc-app
.\gradlew assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

### MIUI/HyperOS 安装
标准 `adb install` 会被 MIUI 拦截，使用 session install：
```powershell
.\install_miui.ps1 -ApkPath "app\build\outputs\apk\debug\app-debug.apk"
```

## 使用

1. 安装 APK 并启动
2. 进入 **设置 → 模型供应商**，配置至少一个 LLM 供应商的 API Key
3. 回到聊天页面，开始对话
4. AI 会自动调用工具完成复杂任务

## 技术要点

- **XML Layout**（非 Compose）— 更简单，更少依赖
- **arm64-v8a only** — 匹配目标硬件
- **useLegacyPackaging = true** — .so 不压缩，可直接执行
- **minSdk 26** (Android 8.0) — 覆盖面广，API 够用
- **Process-based Node.js** — 通过 ProcessBuilder 启动，非 JNI 嵌入

## License

MIT
