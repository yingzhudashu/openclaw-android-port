# Contributing to OpenClaw Android

Thank you for your interest in contributing! This project was built by an AI agent, and we welcome contributions from both humans and AI alike.

## Getting Started

1. Fork the repository
2. Clone your fork: `git clone https://github.com/<your-username>/openclaw-android-port.git`
3. Create a branch: `git checkout -b feature/your-feature`
4. Make your changes
5. Build and test: `cd poc-app && ./gradlew assembleDebug`
6. Commit: `git commit -m "feat: your feature description"`
7. Push: `git push origin feature/your-feature`
8. Open a Pull Request

## Development Setup

### Requirements
- Android Studio (latest stable) or command-line build tools
- JDK 17+
- Android SDK with API 35

### Build
```bash
cd poc-app
./gradlew assembleDebug
```

### Project Structure
- `app/src/main/java/ai/openclaw/poc/` — Kotlin source files
- `app/src/main/assets/openclaw-engine/` — Embedded Node.js gateway
- `app/src/main/res/` — Android resources (layouts, strings, drawables)

## Code Style

- Kotlin with standard Android conventions
- Meaningful variable and function names
- Comments for non-obvious logic

## Commit Messages

Follow [Conventional Commits](https://www.conventionalcommits.org/):
- `feat:` — New feature
- `fix:` — Bug fix
- `docs:` — Documentation changes
- `refactor:` — Code refactoring
- `chore:` — Build/tooling changes

## Reporting Issues

Please include:
- Device model and Android version
- Steps to reproduce
- Expected vs actual behavior
- Screenshots if applicable

## License

By contributing, you agree that your contributions will be licensed under the MIT License.
