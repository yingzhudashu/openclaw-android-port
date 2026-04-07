# OpenClaw Android vs Desktop 功能对比

**最后更新**: v1.7.0 (2026-04-07)

## ✅ 已对齐核心能力
| 能力 | Desktop | Android | 版本 |
|------|---------|---------|------|
| LLM 对话 (SSE 流式) | ✅ | ✅ | v1.0 |
| **Agent 工具循环 (19工具)** | ✅ | **✅** | **v1.7.0** |
| **流式 Agent (SSE+工具)** | ✅ | **✅** | **v1.7.0** |
| Workspace 文件读写 | ✅ | ✅ | v1.0 |
| 命令执行 (exec) | ✅ | ✅ | v1.0 |
| 浏览器控制 (WebView Bridge) | ✅ | ✅ | v1.0 |
| Web 搜索 (Tavily) | ✅ | ✅ | v1.0 |
| Web 抓取 (web_fetch) | ✅ | ✅ | v1.0 |
| 向量记忆 (Embedding) | ✅ | ✅ | v1.0 |
| 记忆读写 | ✅ | ✅ | v1.0 |
| Skills 安装/卸载 | ✅ | ✅ | v1.0 |
| 会话管理 (CRUD) | ✅ | ✅ | v1.0 |
| 图片分析 | ✅ | ✅ | v1.0 |
| Cron 定时任务 | ✅ | ✅ | v1.4.0 |
| Heartbeat 心跳 | ✅ | ✅ | v1.5.0 |
| 通知推送 | ✅ | ✅ | v1.4.0 |
| 多模型 Provider | ✅ | ✅ | v1.0 |
| 深色模式 | ✅ | ✅ | v1.2.0 |

## ⚠️ 部分对齐
| 能力 | Desktop | Android | 差距 |
|------|---------|---------|------|
| 子代理 (Subagent) | 进程隔离 | 多会话模拟 | 无进程隔离，但效果相似 |
| Skill 执行 | 自动加载 SKILL.md | 仅安装到本地 | 需自动注入到 system_prompt |

## ❌ 未对齐（移动端限制）
| 能力 | 原因 |
|------|------|
| 多频道 (Telegram/Discord/飞书) | 需要后端服务器，移动端无法直连 |
| ACP 编码代理 (Cursor/Codex) | 需要桌面端 IDE |
| PTY 终端 | Android 无 PTY |

## 下一步：Skill 自动加载
唯一可行的核心改进：当 AI 收到消息时，自动扫描已安装 Skills 的 SKILL.md，
注入到 system_prompt 中，让 AI 知道自己有哪些技能可用。
