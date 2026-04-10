# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/).

---

## [1.4.0] - 2026-04-10

TTS 语音朗读、全屏图片查看器、PDF 查看器、Tavily API Key 设置、死代码清理。

### Added

#### TTS 语音朗读（Text-to-Speech）
- `ChatFragment` 新增朗读按钮（🔊 图标），点击使用 Android `TextToSpeech` 引擎朗读消息
- 支持中文/英文自动语音切换，`TextToSpeech.OnInitListener` 初始化保护
- 朗读状态栏显示（正在朗读/已停止/失败），底部控制栏可停止朗读
- `TtsManager.kt` 封装完整生命周期管理，防重复初始化
- 英文字符串资源补全（`values-en/strings.xml`）

#### 全屏图片查看器
- `ImageViewerActivity.kt` — 支持缩放、滑动、双击放大、长按保存
- 使用 `PhotoView` 库（`com.github.chrisbanes:PhotoView`）实现流畅手势交互
- 支持 Base64 图片和文件路径加载
- 长按保存图片到相册（`WRITE_EXTERNAL_STORAGE` 权限适配）

#### PDF 查看器
- `PdfViewerActivity.kt` — 使用 Android `PdfRenderer` 实现 PDF 渲染
- 支持翻页、缩放、页面指示器
- 从文件 URI 或 assets 目录加载

#### Tavily API Key 设置
- 设置页新增「Tavily 搜索 API Key」入口
- 弹窗编辑器：密码输入、保存/删除、加载当前配置
- 摘要显示格式：`tvly-xxxx`（末4位脱敏）
- Gateway 删除内置 Tavily Key，改为从配置读取
- `web_search` 工具无 Key 时显示明确中文错误提示
- `news_summary` 工具无 Key 时自动回退到 RSS 多源抓取

### Changed
- `ComprehensiveTest.kt` 已删除（从未被 App 调用的死代码）
- TTS 调试日志标签从 `TTS_TEST` 清理为 `TTS`
- 英文字符串资源补全 6 个缺失项（TTS + memory search）
- `getTavilyKey()` 不再 fallback 到硬编码 Key，无配置时返回空字符串

### Stats
- 7 files changed, +113/-485 lines
- 3 个新 Activity（ImageViewer, PdfViewer, TTS 控制栏）
- Gateway 核心仍为 4000+ 行

---

## [1.3.0] - 2026-04-09

Device Control API, Permission Management, Community Docs, and Critical Bug Fixes.

### Added

#### Device Control API (DeviceControlApi)
- New `DeviceControlApi.kt` — local HTTP server on `127.0.0.1:18791`
- `POST /device/camera/snap` — capture photo via system camera intent, return base64
- `GET /device/location` — get current GPS coordinates
- `GET /device/notifications` — read recent system notifications
- `CameraCaptureActivity.kt` — transparent activity for background camera requests
- `PhotoStore.kt` — photo buffer with async wait and timeout protection
- `ComprehensiveTest.kt` — integration test suite (Gateway API, Browser Bridge, Device API, stress test)

#### Permission Management
- `PermissionManager.kt` — centralized runtime permission manager
  - Location (ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION)
  - Camera (CAMERA)
  - Microphone (RECORD_AUDIO)
  - Notifications (POST_NOTIFICATIONS, Android 13+)
  - Storage (READ_MEDIA_IMAGES / READ_EXTERNAL_STORAGE, version-adaptive)
- Auto-request missing permissions on first launch
- Settings page shows permission status with link to system settings
- `AndroidManifest.xml` updated with complete permission declarations

#### Community Docs
- `LICENSE` (MIT)
- `CONTRIBUTING.md` — contribution guide with dev setup, code standards, PR checklist
- `SECURITY.md` — security policy with known limitations and best practices
- `CODE_OF_CONDUCT.md` — Contributor Covenant 2.1
- `.gitignore` — comprehensive rules covering Android/IDE/OS/runtime data
- Rewrote `README.md` with architecture, port allocation, tech stack, dev guide, roadmap
- Updated `CHANGELOG.md` to Keep a Changelog format

#### Gateway Enhancements
- Cron task persistence (`cron_tasks.json`), survives restarts
- Gateway version bumped to `1.3.0-android`

### Fixed

#### Multimodal Image Token Estimation (Critical Bug)
- **Issue**: `	runcateMessages` used `JSON.stringify(m.content)` to estimate tokens. A 500KB image's base64 (~680K chars) was miscounted as ~227,000 tokens, far exceeding `MAX_CONTEXT_TOKENS` (120,000), causing the image message to be dropped. The LLM only received plain text "Please analyze this image".
- **Fix**: New `estimateMessageTokens()` function distinguishes `	ype: 'text'` from `	ype: 'image_url'`, counting images at ~800 tokens each (Vision API standard).
- **Enhancement**: Truncation logic now always preserves the latest user message.

#### Health Check Lifecycle
- Fixed health check continuing to schedule after `onDestroyView` (Fix #5/#9)
- New `healthCheckRunning` flag stops scheduling when view is destroyed

#### Session Management
- `createNewSession()` converted to `suspend` function, returns Boolean for success/failure
- Welcome message persisted to Gateway server, survives session switches
- Fixed pending file/image restoration after retry (Fix #7/#8)

### Stats
- 25 files changed, +3658/-869 lines
- 5 new Kotlin files
- Gateway core at 4000+ lines

---

## [1.2.0] - 2026-04-08

Agent capabilities aligned with desktop core.

### Added
- **Sub-agent system** — sessions_spawn/sessions_send/sessions_list/sessions_yield
  - Dynamic sub-agent creation with independent Agent Loop
  - Yield to wait for all sub-agents with timeout protection (default 300s)
- **Process management** — process tool (list/poll/log/write/kill)
  - exec: background mode + cwd parameter
  - Buffered output + auto-cleanup
- **session_status** — view model/version/memory/runtime

### Improved
- Tools increased from 22 to 28
- Gateway versioning unified to semantic versioning

---

## [1.1.0] - 2026-04-08

Core agent capabilities aligned with desktop.

### Agent Core
- SSE streaming (token-by-token output with thinking/reasoning)
- Full multi-step tool loop (agentChatStream + streamLLMWithTools)
- 22 tools: web_search, web_fetch, exec, file_read/write/edit/delete, create_dir, browser_navigate/eval/click/type/content/screenshot, news_summary, skill_search/install, memory_search/store
- Vector memory (embedding + cosine similarity)
- Skills auto-loading (scans skills/ directory into system_prompt)
- MEMORY.md + datetime auto-injection

### Multi-Model
- 5 providers (DashScope/OpenAI/Anthropic/DeepSeek/SiliconFlow)
- Model Fallback (auto-switch on failure)
- In-chat model switching
- Token estimation + context auto-truncation

### UX
- Dark mode (Material Design)
- Code block copy button
- Markdown links + bare link click
- Message search (highlight + auto-scroll)
- Chat export as Markdown
- Date separators
- Long-press session: rename/delete
- MEMORY.md editor

### System
- Cron tasks (WorkManager + notifications + UI)
- Heartbeat health checks
- Agent templates (5 preset sub-agent roles)
- Gateway Watchdog (auto-restart)
- Foreground Service (persistent)
- Backup/restore (config + memory + sessions + skills)
- Config protection (API keys survive upgrade/restore)
- WebViewBridge browser control (port 18790)
- Doctor/Selftest API

### Fixes
- news_summary uses RSS/Tavily multi-source (no browser bridge dependency)
- Client done event content cleared to empty
- Android Node.js missing Intl API (use getTimezoneOffset)
- Gateway connection timeout (socket.setTimeout + keepAliveTimeout)
- Config loss (extractEngine overwrites openclaw.json)
- UTF-8 encoding corruption (PowerShell replace breaks special chars)
- Dark mode color gaps (fab_bg/fab_icon etc.)
- web_fetch User-Agent rejected by server
- Tool timeout mechanism + max_agent_steps anti-infinite-loop

### Removed
- Voice input (fabVoice + RECORD_AUDIO permission)

---

## [1.0.0] - 2026-04-06

Initial release.

### Features
- Basic chat UI (Fragment + RecyclerView + XML)
- Embedded Node.js Gateway
- Multi-session management (create/switch/rename)
- Settings page (model provider config, memory mode, backup/restore, language)
- File analysis (image recognition)
- Multi-language support (Chinese/English)
- Network status monitoring