# OpenClaw Android

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Version](https://img.shields.io/github/v/release/yingzhudashu/openclaw-android-port?label=version)](https://github.com/yingzhudashu/openclaw-android-port/releases)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg)](https://developer.android.com)
[![Architecture](https://img.shields.io/badge/Arch-arm64--v8a-blue.svg)]()

**AI 个人助手，完全在手机端侧运行。**

OpenClaw Android 将完整的 AI Agent 能力带到 Android 手机上。通过内嵌 Node.js Gateway，实现了与桌面端对齐的核心功能——包括多步工具调用、子代理系统、向量记忆、Skills 自动加载、浏览器控制，以及设备控制 API（相机、GPS、通知读取）。

> 🤖 本项目由 [OpenClaw](https://openclaw.ai) AI 助手独立开发，人类负责需求定义和验收。

## ✨ 核心特性

### 🧠 Agent 智能体能力
- **SSE 流式对话** — 实时逐字输出，支持 thinking/reasoning 过程展示
- **28+ 工具调用** — 文件操作、命令执行、网页搜索/抓取、浏览器控制、新闻摘要、技能管理、记忆搜索等
- **多步工具循环** — LLM 自主决策调用工具，最多 25 步自动推理
- **子代理系统** — 动态创建子代理执行独立任务（sessions_spawn/send/list/yield）
- **进程管理** — 后台执行命令 + 进程监控（process tool）
- **向量记忆** — embedding + cosine similarity 语义搜索
- **Skills 自动加载** — 扫描 skills/ 目录注入 system_prompt

### 📷 设备控制 API（v1.3.0 新增）
- **相机拍照** — 通过 ` + "" + "/device/camera/snap" + "" + ` 调用系统相机，返回 base64 图片
- **GPS 定位** — 通过 ` + "" + "/device/location" + "" + ` 获取设备位置
- **通知读取** — 通过 ` + "" + "/device/notifications" + "" + ` 读取最近通知
- 配套透明 Activity（CameraCaptureActivity）和照片缓冲区（PhotoStore）
- 本地 HTTP 服务器运行在 ` + "" + "127.0.0.1:18791" + "" + `