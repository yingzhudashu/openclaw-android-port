> **This project was independently developed by [OpenClaw](https://openclaw.ai) AI agent. The human contributor served solely as a product manager, providing requirements through natural language.**

# OpenClaw Android

An open-source Android client for [OpenClaw](https://github.com/openclaw/openclaw) — a personal AI assistant platform.

## ✨ Features

- **Multi-provider LLM Support** — Configure multiple AI providers (Alibaba Bailian, OpenAI, Anthropic, DeepSeek) with independent API keys and model lists
- **Session Management** — Create, switch, rename, and delete chat sessions with persistent history
- **Markdown Rendering** — Rich message display with code highlighting, tables, and inline formatting
- **Image Analysis** — Send images for AI-powered visual analysis
- **File Analysis** — Attach and analyze text files within conversations
- **Embedding Model** — Optional vector memory with configurable embedding providers
- **Workspace Files** — Edit SOUL.md, USER.md, HEARTBEAT.md, AGENTS.md, TOOLS.md directly in-app
- **Skills Management** — Install and manage AI skills
- **Backup & Restore** — One-tap backup to `Download/OpenClaw/` with restore file picker
- **Bilingual UI** — Full Chinese and English localization
- **Embedded Engine** — Self-contained Node.js gateway runs locally on-device

## 📸 Screenshots

_Coming soon_

## 🚀 Getting Started

### Prerequisites

- Android 7.0 (API 24) or higher
- Internet connection for LLM API calls

### Installation

1. Download the latest APK from [Releases](https://github.com/yingzhudashu/openclaw-android-port/releases)
2. Install on your Android device
3. Open the app → Settings → Configure at least one model provider with an API key
4. Set your default model and start chatting

### Build from Source

```bash
git clone https://github.com/yingzhudashu/openclaw-android-port.git
cd openclaw-android-port/poc-app
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

## 🏗️ Architecture

```
poc-app/
├── app/src/main/
│   ├── java/ai/openclaw/poc/     # Kotlin source
│   │   ├── MainActivity.kt       # Single-activity host
│   │   ├── ChatFragment.kt       # Chat UI & session management
│   │   ├── SettingsFragment.kt   # Settings & provider configuration
│   │   ├── MessageAdapter.kt     # Message rendering (Markdown, code)
│   │   ├── Session.kt            # Session data model & adapter
│   │   ├── ApiClient.kt          # HTTP client utilities
│   │   └── LocaleHelper.kt       # i18n language switching
│   ├── assets/openclaw-engine/
│   │   ├── android-gateway.cjs   # Embedded Node.js gateway
│   │   └── openclaw.json         # Engine configuration
│   └── res/
│       ├── layout/               # XML layouts
│       ├── values/               # Chinese strings (default)
│       └── values-en/            # English strings
```

### Key Design Decisions

- **On-device Gateway**: The LLM gateway runs as an embedded Node.js process inside the app, keeping all configuration and session data local.
- **Provider-Model Coupling**: Models belong to providers. Deleting a provider removes its models from the selection list.
- **Session Isolation**: Each async operation captures the session ID at dispatch time and verifies it on completion to prevent cross-session contamination during rapid switching.
- **Single Source of Truth**: The gateway `config.json` is the authoritative source for model, provider, and all settings. SharedPreferences serves only as a cache for the message adapter.

## ⚙️ Configuration

### Supported Providers

| Provider | Base URL | Example Models |
|----------|----------|----------------|
| Alibaba Bailian | `https://dashscope.aliyuncs.com/compatible-mode/v1` | qwen3.5-plus |
| OpenAI | `https://api.openai.com/v1` | gpt-4o |
| Anthropic | `https://api.anthropic.com/v1` | claude-sonnet-4-6 |
| DeepSeek | `https://api.deepseek.com/v1` | deepseek-chat |

You can add custom providers with any OpenAI-compatible API endpoint.

### Embedding Providers

| Provider | Base URL | Model |
|----------|----------|-------|
| Bailian | `https://dashscope.aliyuncs.com/compatible-mode/v1` | text-embedding-v3 |
| OpenAI | `https://api.openai.com/v1` | text-embedding-3-small |
| SiliconFlow | `https://api.siliconflow.cn/v1` | BAAI/bge-m3 |

## 🔒 Privacy & Security

- **No hardcoded API keys** — All credentials are entered by the user and stored only on-device
- **Local storage** — Sessions, configuration, and backups stay on the device
- **No telemetry** — The app does not collect or transmit usage data

## 📋 Changelog

See [CHANGELOG.md](CHANGELOG.md) for release history.

## 🤝 Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

## 📄 License

This project is licensed under the MIT License — see [LICENSE](LICENSE) for details.

## 🙏 Acknowledgments

- [OpenClaw](https://github.com/openclaw/openclaw) — The AI assistant platform this client is built for
- Built entirely by an AI agent (OpenClaw) with human product management
