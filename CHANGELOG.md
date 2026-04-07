# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.5.0] - 2026-04-07

### Added
- **💓 Heartbeat Worker** — WorkManager-based periodic heartbeat (30min), mirrors desktop HEARTBEAT.md polling
- **🔍 Memory Search API** — Gateway endpoint `/api/memory/search?q=` for grep-based memory search
- **Heartbeat Toggle** — Settings switch to enable/disable heartbeat monitoring
- **Notification Alerts** — Heartbeat results pushed when AI has something to report

### Improved
- Gateway: added memory search endpoint
- Settings: heartbeat monitoring toggle

## [1.4.0] - 2026-04-07

### Added
- **⏰ Cron Tab** — New tab for scheduled tasks (create/edit/delete/toggle)
- **WorkManager Integration** — System-level scheduling, survives app kill
- **Notification Push** — Cron results pushed via notification channel
- **Date Separators** — "Today"/"Yesterday"/date between messages
- **Dynamic Send/Voice** — Input-aware button switching

### Gap Analysis
- Added FEATURE_GAP.md documenting desktop vs Android feature parity
- Cron/Heartbeat gap now closed

## [1.3.1] - 2026-04-07

### Added
- **Dynamic Send/Voice Button** — Empty input shows mic, typing shows send
- **Date Separators** — "Today", "Yesterday", or date shown between messages from different days
- **Swipe to Delete** — Left-swipe on any message to remove it

## [1.3.0] - 2026-04-07

### Added
- **Message Search** — Search bar in chat with real-time highlighting (yellow background on matches)
- **Chat Export** — Export conversations as Markdown files to `Download/OpenClaw/`
- Export option added to session long-press menu

## [1.2.0] - 2026-04-07

### Added
- **Dark Mode** — Full dark theme with toggle in Settings, 106 semantic color replacements, DayNight support
- **Voice Input** — Microphone button next to send, uses Android SpeechRecognizer
- **App Shortcuts** — Long-press app icon for "New Chat" and "Voice Ask" shortcuts
- **MEMORY.md Editor** — New Memory section in Settings for editing long-term memory
- **Clear Context** — Third option in session menu to clear conversation context
- **RECORD_AUDIO permission** for voice input

### Changed
- 106 hardcoded colors replaced with semantic color resources for theme support
- Night mode colors for all UI elements

## [1.1.0] - 2026-04-07

### Added
- **SSE Streaming** — Real-time streaming responses with typing effect
- **Reasoning Display** — Collapsible thinking/reasoning section in AI messages
- **Tool Call Visualization** — Show tool usage steps during agent execution
- **Network Status Banner** — Red banner when offline, auto-hide on reconnect with animation
- **Exponential Backoff Retry** — Auto-retry on network errors (1s→3s, max 2 attempts)
- **Send Failed Retry** — Tap-to-retry button on failed user messages
- **Gateway Health Check** — 30-second heartbeat, toast on 3 consecutive failures
- **Draft Saving** — Input text saved/restored per session via SharedPreferences
- **Code Block Copy Button** — `[Copy Code]` link after each code block
- **Link Click Handling** — Markdown `[text](url)` and bare `https://` links open in browser
- **LinkMovementMethod** — Clickable spans in AI messages
- **ACCESS_NETWORK_STATE permission** for connectivity monitoring

### Fixed
- HTML error responses from providers now show friendly error messages
- Client timeout increased to 10 minutes for long agent runs

## [1.0.0] - 2026-04-06

### Added
- Multi-provider LLM configuration (Bailian, OpenAI, Anthropic, DeepSeek)
- Custom provider support with editable model lists
- Default model selection with provider-model coupling
- Embedding model configuration (Bailian, OpenAI, SiliconFlow)
- Session management: create, switch, rename, delete
- Persistent chat history stored on-device via embedded gateway
- Markdown rendering with code syntax highlighting
- Image analysis (camera + gallery)
- File analysis with text extraction
- Workspace file editor (SOUL.md, USER.md, HEARTBEAT.md, AGENTS.md, TOOLS.md)
- Skills installation and management
- Memory mode selection (basic / vector)
- Max tool call steps configuration
- One-tap backup to Download/OpenClaw/ with path display
- One-tap restore with file picker opening to backup folder
- Bilingual UI (Chinese default, English)
- Bot avatar tap to switch model/provider per-session
- Session isolation for concurrent async operations
- Welcome message persistence across session switches
