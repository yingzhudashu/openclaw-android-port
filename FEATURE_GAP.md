# OpenClaw Android vs Desktop 功能对比

## 已对齐 ✅
| 功能 | Desktop | Android | 状态 |
|------|---------|---------|------|
| 多 LLM Provider | ✅ | ✅ 设置页配置 | 完成 |
| SSE 流式响应 | ✅ | ✅ Phase 1 | 完成 |
| 会话管理 | ✅ | ✅ 创建/切换/重命名/删除 | 完成 |
| Markdown 渲染 | ✅ | ✅ 代码高亮+加粗+列表 | 完成 |
| Workspace 文件编辑 | ✅ | ✅ SOUL/USER/HEARTBEAT/AGENTS/TOOLS/MEMORY | 完成 |
| 图片分析 | ✅ | ✅ 拍照/相册 | 完成 |
| 文件分析 | ✅ | ✅ 文本文件 | 完成 |
| 备份恢复 | ✅ | ✅ Download/OpenClaw/ | 完成 |
| 深色模式 | ✅ | ✅ DayNight + 开关 | 完成 |
| 代码块复制 | ✅ | ✅ [复制代码] | 完成 |
| 链接点击 | ✅ | ✅ Markdown+裸链接 | 完成 |
| 网络弹性 | ✅ | ✅ 重试+横幅+健康检查 | 完成 |
| 语音输入 | - | ✅ Android 独有 | 完成 |
| 快捷指令 | - | ✅ App Shortcuts | 完成 |
| 搜索消息 | ✅ | ✅ 实时高亮 | 完成 |
| 导出聊天 | - | ✅ Markdown 导出 | 完成 |

## 待对齐 ❌
| 功能 | Desktop | Android | 优先级 | 难度 |
|------|---------|---------|--------|------|
| **定时任务 (Cron)** | ✅ openclaw cron | ✅ v1.4.0 WorkManager | ✅ 已关闭 | - |
| **子代理 (Subagent)** | ✅ sessions_spawn | ⚠️ 多会话模拟 | 🟡 中 | 高 |
| **Skills 执行** | ✅ skill install/run | ✅ 列表+详情+安装+删除 | ✅ 已关闭 | - |
| **工具调用 (Tools)** | ✅ web_fetch/exec/browser | ⚠️ 仅显示调用日志 | 🟡 中 | 高 |
| **通知推送** | ✅ 频道消息 | ✅ v1.4.0 NotificationChannel | ✅ 已关闭 | - |
| **Heartbeat 心跳** | ✅ HEARTBEAT.md 轮询 | ✅ v1.5.0 WorkManager | ✅ 已关闭 | - |
| **多频道 (Channel)** | ✅ Telegram/Discord/飞书等 | ❌ 仅本地 | 🟡 中 | 高 |
| **Memory 搜索** | ✅ memory_search | ✅ v1.5.0 API | ✅ 已关闭 | - |
| **ACP 编码代理** | ✅ Cursor/Codex | ❌ 无 | 🔵 低 | 高 |
| **Browser 控制** | ✅ headless browser | ❌ 无 | 🔵 低 | 高 |

## 实施计划
### Round 1: 定时任务 + Heartbeat (最高优先)
- Gateway: `/api/cron` CRUD API (已有基础)
- UI: Cron 管理页 — 列表/创建/编辑/删除/开关
- WorkManager 或 AlarmManager 实现定时触发
- Heartbeat 轮询: 定时读 HEARTBEAT.md 并执行

### Round 2: Skills 增强
- Skills 安装/卸载 (调用 /api/skills)
- Skills 详情页
- Skill 执行日志

### Round 3: 子代理基础
- 会话中显示子代理状态
- 子代理结果回显

### Round 4: 通知推送
- Foreground Service 保持引擎运行
- NotificationChannel 推送 AI 回复
