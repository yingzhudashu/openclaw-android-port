# OpenClaw Android PoC

Minimal Android app to verify that Node.js can run on Android via `libnode.so`.

## What it does

1. **Extracts** the engine bundle from `assets/engine/` to internal storage
2. **Launches** Node.js via `libnode.so` running `android-entry.mjs`
3. **Health-checks** the engine at `http://127.0.0.1:18789/health`
4. **Displays** engine status and logs in a simple UI

## Project structure

```
poc-app/
├── app/
│   ├── src/main/
│   │   ├── java/ai/openclaw/poc/
│   │   │   ├── MainActivity.kt        # UI: start/stop/health buttons + log
│   │   │   └── NodeRunner.kt          # Node.js process lifecycle manager
│   │   ├── jniLibs/arm64-v8a/         # Place libnode.so here
│   │   ├── assets/engine/             # Place engine bundle here
│   │   ├── res/
│   │   │   ├── layout/activity_main.xml
│   │   │   └── values/strings.xml
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

## Before building

### 1. Add `libnode.so`

Place the arm64 Node.js shared library at:
```
app/src/main/jniLibs/arm64-v8a/libnode.so
```

### 2. Add engine bundle (optional)

Place your OpenClaw engine bundle at:
```
app/src/main/assets/engine/
├── android-entry.mjs
├── node_modules/
└── ...
```

If no engine bundle is present, the app creates a **placeholder** `android-entry.mjs`
that starts a minimal HTTP health server — enough to verify Node.js works.

## Build

```bash
# On Windows
set JAVA_HOME=D:\Android\Android Studio\jbr
cd D:\AIhub\openclaw-android-port\poc-app
.\gradlew assembleDebug

# APK output:
# app/build/outputs/apk/debug/app-debug.apk
```

## Key design decisions

- **XML layout** (not Compose) — simpler, fewer dependencies
- **arm64-v8a only** — matches target hardware
- **useLegacyPackaging = true** — keeps .so uncompressed in APK for direct execution
- **minSdk 26** (Android 8.0) — broad enough, modern enough
- **Coroutines** for async engine start/health check
- **Process-based** Node.js execution (not JNI embedding)

## Architecture

```
MainActivity ──→ NodeRunner
    │                │
    │ UI events      │ start() / stop() / healthCheck()
    │                │
    │ ←── onLog()    │ ProcessBuilder → libnode.so android-entry.mjs
    │ ←── onState()  │
    └────────────────┘
```

`NodeRunner` manages the full lifecycle:
- Asset extraction (assets → filesDir)
- Process creation with environment variables
- stdout/stderr streaming to logcat + UI
- HTTP health checking
- Graceful + forceful shutdown
