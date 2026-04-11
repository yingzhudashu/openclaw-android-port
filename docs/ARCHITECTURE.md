# 架构设计

## 整体架构

```
┌─────────────────────────────────────────────────────┐
│              Android App (Kotlin)                    │
│                                                     │
│  ChatFragment ──── MessageAdapter                   │
│  SettingsFragment ──── GatewayApi                   │
│  StatusFragment ──── GatewayService                 │
│       │                    │                        │
│       ▼                    ▼                        │
│  ┌──────────┐    ┌──────────────────────┐           │
│  │GatewayApi│───▶│  NodeRunner           │           │
│  │(HTTP)    │    │  (ProcessBuilder)     │           │
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
│  │  ├── 28+ Tools                      │            │
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

## 端口分配

| 端口 | 服务 | 说明 |
|------|------|------|
| 18789 | Gateway | AI Agent 核心服务，LLM 通信 + 工具执行 |
| 18790 | WebViewBridge | 浏览器控制桥接（agent 操作浏览器） |
| 18791 | DeviceControlApi | 设备控制 API（相机、GPS、通知） |

## 数据流

### 对话流程

```
用户输入 → ChatFragment → GatewayClient (SSE)
                            ↓
                      Node.js Gateway
                            ↓
                   LLM Provider API
                            ↓
                   Agent Loop (工具调用)
                            ↓
                   SSE chunks → ChatFragment → UI 渲染
```

### 设置流程

```
SettingsFragment → GatewayApi (HTTP)
                      ↓
                Node.js Gateway
                      ↓
                读写 openclaw.json / skills/ / memory/
                      ↓
                返回 JSON 结果
```

## 关键组件

### GatewayApi.kt（v1.6.0 新增）
集中式 HTTP 客户端，封装所有 Gateway 通信。替代 SettingsFragment 中的重复 HTTP 代码。

### GatewayClient.kt（v1.6.0 新增）
SSE 流式通信客户端，用于对话场景的实时逐字输出。

### MarkdownRenderer.kt（v1.6.0 新增）
Markdown → Spanned 渲染器，支持标题、粗体、斜体、代码块、列表、链接。

### NodeRunner.kt
管理 Node.js 进程生命周期（启动、监控、重启）。

### android-gateway.cjs
Node.js Gateway 核心（4000+ 行），包含 LLM 客户端、Agent Loop、工具系统、记忆、Skills 等。

## 安全边界

- 所有端口仅绑定 `127.0.0.1`，外部不可访问
- API Key 由用户输入，不硬编码
- 数据仅存储在设备本地
- 无遥测、无外部数据收集
