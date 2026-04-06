# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
