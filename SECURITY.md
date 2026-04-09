# Security Policy

## Supported Versions

| Version | Security Updates |
|---------|-----------------|
| 1.3.x   | Yes             |
| < 1.3   | No              |

## Reporting a Vulnerability

1. Do NOT describe the vulnerability in a public issue
2. Open a report via [GitHub Issues](https://github.com/yingzhudashu/openclaw-android-port/issues) with title prefixed by [Security]
3. Include: affected component, steps to reproduce, potential impact, suggested fix
4. We commit to initial response within 72 hours.

## Security Design

### API Key Protection
- Stored in app-private directory (Context.MODE_PRIVATE)
- No hardcoded credentials in code or repository
- .gitignore excludes configuration files

### Network Security
- Gateway binds only to 127.0.0.1 (loopback)
- Device Control API (18791) and WebViewBridge (18790) also loopback-only
- LLM communication uses HTTPS

### Permission Minimization
- Only necessary runtime permissions requested
- Users can view/manage permissions in Settings

### Known Limitations
- APK can be decompiled; API Keys extractable on rooted devices
- Debug APK contains unobfuscated code; Release should use ProGuard/R8