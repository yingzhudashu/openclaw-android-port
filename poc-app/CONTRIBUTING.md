# Contributing Guide

感谢你愿意为 OpenClaw Android 项目做出贡献！以下指南帮助你高效、规范地参与开发。

## 行为准则

本项目遵循 [Contributor Covenant 行为准则](CODE_OF_CONDUCT.md)。参与即代表你同意遵守该准则，包括尊重他人、包容差异、建设性沟通。

## 如何贡献

### 报告 Bug

1. 在 [Issues](https://github.com/yingzhudashu/openclaw-android-port/issues) 中搜索确认问题未被报告过
2. 创建新 Issue，包含：
   - **环境信息**：Android 版本、设备型号、App 版本（设置页可查看）
   - **复现步骤**：清晰的步骤描述
   - **预期行为** vs **实际行为**
   - **日志**：`adb logcat | grep -E "NodeRunner|Gateway|ChatFragment"` 的输出
   - **截图/录屏**（可选但强烈推荐）

### 提出新功能

1. 先开一个 Issue 讨论，描述需求和场景
2. 标注 `[Feature Request]` 前缀
3. 等待维护者讨论确认后再开始编码

### 提交 Pull Request

#### 开发环境搭建

```bash
# 克隆仓库
git clone https://github.com/yingzhudashu/openclaw-android-port.git
cd openclaw-android-port/poc-app

# Android Studio 打开项目，或使用命令行
# 确保 JAVA_HOME 指向 Android Studio 的 JBR
$env:JAVA_HOME = "D:\Android\Android Studio\jbr"  # Windows PowerShell
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr"  # macOS

# 编译 Debug APK
./gradlew assembleDebug
```

#### 代码规范

- **Kotlin 代码**：遵循 [Kotlin 官方编码规范](https://kotlinlang.org/docs/coding-conventions.html)
- **Node.js 代码**：遵循 [StandardJS 风格](https://standardjs.com/)，使用 CommonJS（`.cjs` 后缀）
- **命名**：Kotlin 用 `camelCase`/`PascalCase`，JS 用 `camelCase`
- **注释**：公共 API 必须有文档注释（`/** ... */`），复杂逻辑必须有行内注释
- **字符串**：所有用户可见文本必须使用 `strings.xml` 资源，支持多语言

#### Git Commit 规范

使用 [Conventional Commits](https://www.conventionalcommits.org/) 格式：

```
<type>(<scope>): <description>

[optional body]
```

**Type 可选**：`feat`、`fix`、`docs`、`style`、`refactor`、`perf`、`test`、`ci`、`chore`

**示例**：
```
feat(gateway): add Device Control API for camera/location
fix(chat): fix image token estimation in truncateMessages
docs(readme): update architecture diagram for v1.3.0
```

#### PR Checklist

提交 PR 前，请确认：

- [ ] 代码能成功编译（`./gradlew assembleDebug` 通过）
- [ ] 没有在真实设备上测试过核心功能
- [ ] Commit message 符合 Conventional Commits 规范
- [ ] 新增功能有对应文档更新
- [ ] 没有提交敏感信息（API Key、Token 等）

### 分支策略

- `main` — 稳定分支，所有发布版本基于此
- `dev` — 开发分支（如适用），日常功能合并到此
- `feature/*` — 功能分支，完成后合并到 `dev`
- `fix/*` — 修复分支，完成后合并到 `dev` 或 `main`（hotfix）

## 架构概览

```
Android App (Kotlin)          Node.js Gateway (CJS)
├── ChatFragment              ├── /api/agent/chat (SSE)
├── SettingsFragment          ├── /api/sessions
├── StatusFragment            ├── /api/cron
├── GatewayService            ├── /api/memory
├── NodeRunner                ├── 28 个工具
├── DeviceControlApi (:18791) ├── Agent Loop
├── WebViewBridge (:18790)    └── Skills / Memory
└── PermissionManager
```

详见 [README.md](README.md)。

## 测试

### 单元测试

```bash
./gradlew testDebugUnitTest
```

### 集成测试

App 内置 Comprehensive Test 套件（`ComprehensiveTest.kt`），可在设置页一键运行，覆盖：
- Gateway API（健康检查、聊天、会话、工具列表）
- Browser Bridge
- Device Control API
- 多请求并发压力测试

### 手动测试

建议在真实 Android 设备上测试以下场景：
1. 首次安装 → 权限请求 → 配置 API Key → 发送消息
2. 图片上传 → AI 识别分析
3. 流式输出 → thinking/reasoning 显示
4. 网络断开 → 重连恢复
5. 子代理 → 动态创建并等待结果
6. Cron 任务 → 定时执行通知

## 发布流程

1. 更新 `CHANGELOG.md`
2. 修改 `app/build.gradle.kts` 的 `versionCode` 和 `versionName`
3. 创建 Git tag（语义化版本）：`git tag v1.x.x`
4. 推送并创建 GitHub Release

## 联系方式

- **Issues**: [GitHub Issues](https://github.com/yingzhudashu/openclaw-android-port/issues)
- **项目主页**: https://github.com/yingzhudashu/openclaw-android-port

## 致谢

本项目由 [OpenClaw](https://github.com/openclaw/openclaw) AI 助手开发，人类负责需求定义和验收。感谢所有贡献者！
