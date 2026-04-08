# Security Policy

## Supported Versions

| Version | Supported          |
|---------|--------------------|
| 1.2.x   | ✅ Current          |
| 1.1.x   | ⚠️ Security fixes only |
| 1.0.x   | ❌ End of life       |

## Reporting a Vulnerability

If you discover a security vulnerability, please report it responsibly:

1. **DO NOT** open a public GitHub issue
2. Email: [yingzhudashu@gmail.com](mailto:yingzhudashu@gmail.com)
3. Include:
   - Description of the vulnerability
   - Steps to reproduce
   - Potential impact
   - Suggested fix (if any)

We aim to respond within 48 hours and provide a fix within 7 days for critical issues.

## Security Design

### API Key Storage
- API keys are stored **only on the device** in the app's private storage
- Keys are never transmitted to any server other than the configured LLM provider
- The repository contains **no hardcoded credentials** — all `api_key` fields in `openclaw.json` are empty strings

### Network Communication
- All LLM API calls use HTTPS
- The Gateway binds to `127.0.0.1:18789` (localhost only) — not accessible from external networks
- WebViewBridge binds to `127.0.0.1:18790` (localhost only)

### Data Privacy
- No telemetry or analytics
- No data collection or transmission beyond user-initiated LLM API calls
- All conversation data stored locally on device
- Backup files are stored in the app's private directory

### Code Execution
- The `exec` tool runs commands in the app's sandboxed environment
- Destructive commands (`rm -rf /`, `mkfs`, etc.) are blocked
- Background processes have automatic timeout protection (default 300s)

## Automated Security Checks

The repository includes:
- `.gitignore` rules to prevent accidental commit of API keys and build artifacts
- Empty API key fields in the shipped `openclaw.json` template
