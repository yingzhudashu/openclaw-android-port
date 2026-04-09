# Contributing to OpenClaw Android

Thank you for your interest in contributing! This project was built by an AI agent, and we welcome contributions from both humans and AI alike.

## Code of Conduct

This project follows the [Contributor Covenant Code of Conduct](CODE_OF_CONDUCT.md). By participating, you agree to abide by its terms.

## How to Contribute

### Reporting Bugs

1. Search [Issues](https://github.com/yingzhudashu/openclaw-android-port/issues) to confirm the bug hasn't been reported
2. Open a new issue including:
   - **Environment**: Android version, device model, app version
   - **Steps to reproduce**
   - **Expected vs actual behavior**
   - **Logs**: ``adb logcat | grep -E "NodeRunner|Gateway|ChatFragment"`
   - Screenshots/screen recordings (recommended)

### Suggesting Features

1. Open an issue first to discuss the idea
2. Prefix with `[Feature Request]`
3. Wait for maintainer confirmation before coding

### Pull Requests

#### Development Setup

````bash
git clone https://github.com/yingzhudashu/openclaw-android-port.git
cd openclaw-android-port/poc-app

# Set JAVA_HOME to Android Studio's JBR
./gradlew assembleDebug
```

#### Code Standards

- **Kotlin**: Follow [official Kotlin conventions](https://kotlinlang.org/docs/coding-conventions.html)
- **Node.js**: StandardJS style, CommonJS format (`.cjs` extension)
- **Naming**: Kotlin uses `camelCase`/`PascalCase`, JS uses `camelCase`
- **Comments**: All public APIs must have doc comments; complex logic must have inline comments
- **Strings**: All user-visible text must use `strings.xml` resources (multi-language support)

#### Commit Messages

Follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <description>
```

Types: ``feat`, ``fix`, `docs`, `style`, ``refactor`, `perf`, `	est`, `ci`, `chore`

#### PR Checklist

- [ ] Code compiles (`./gradlew assembleDebug` passes)
- [ ] Core features tested on real device
- [ ] Commit messages follow Conventional Commits
- [ ] Documentation updated for new features
- [ ] No sensitive data committed (API keys, tokens, etc.)

### Branch Strategy

- `main` — stable branch, all releases based on this
- ``feature/*` — feature branches, merged to `main`
- ``fix/*` — fix branches, merged to `main` (hotfix)

## Testing

### Integration Test

The app includes a built-in test suite (`ComprehensiveTest.kt`) accessible from Settings:
- Gateway API (health, chat, sessions, tools)
- Browser Bridge connectivity
- Device Control API availability
- Concurrent request stress test

### Manual Testing

Recommended scenarios on real Android device:
1. First install → permission requests → API Key config → send message
2. Image upload → AI analysis
3. Streaming output → thinking/reasoning display
4. Network disconnect → reconnect recovery
5. Sub-agents → dynamic creation and waiting
6. Cron tasks → scheduled execution notifications

## Release Process

1. Update `CHANGELOG.md`
2. Bump ``versionCode` and ``versionName` in ``app/build.gradle.kts`
3. Create Git tag: `git tag v1.x.x`
4. Push and create GitHub Release

## License

By contributing, you agree that your contributions will be licensed under the MIT License.