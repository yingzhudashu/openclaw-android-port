# Changelog

## v1.2.0 (2026-04-08)

Agent 能力对齐桌面端核心。

### 新增
- **子代理系统** — sessions_spawn/sessions_send/sessions_list/sessions_yield 4个工具
  - Agent 可在对话中动态创建子代理执行独立任务
  - 子代理有自己的 Agent Loop（支持工具调用）
  - 支持 yield 等待所有子代理完成
  - 超时保护（默认 300s）
- **进程管理** — process 工具（list/poll/log/write/kill）
  - exec 工具新增 background 模式（后台运行）
  - exec 新增 cwd 参数（工作目录）
  - 后台进程输出缓冲 + 自动清理
- **session_status** — 查看模型/版本/内存/运行时间等

### 改进
- 工具数量从 22 增加到 28
- Gateway 版本号体系统一为语义化版本

---

## v1.1.0 (2026-04-08)

v1.0.0 之后的全面升级，核心 Agent 能力对齐桌面端。

### 新功能

**Agent 核心**
- SSE 流式对话（逐字输出，支持 thinking/reasoning）
- 完整的多步工具循环（agentChatStream + streamLLMWithTools）
- 22 个工具：web_search、web_fetch、exec、file_read/write/edit/delete、create_dir、browser_navigate/eval/click/type/content/screenshot、news_summary、skill_search/install、memory_search/store
- 向量记忆（embedding + cosine similarity 语义搜索）
- Skills 自动加载（扫描 skills/ 目录注入 system_prompt）
- MEMORY.md + 日期时间自动注入 system_prompt

**多模型**
- 5 家供应商支持（百炼/OpenAI/Anthropic/DeepSeek/SiliconFlow）
- 模型 Fallback（主模型失败自动切换备用）
- 聊天内快速切换模型
- Token 估算与上下文自动截断

**用户体验**
- 深色模式（完整 Material Design 暗色主题）
- 代码块复制按钮
- Markdown 链接 + 裸链接点击跳转
- 消息搜索（实时高亮 + 自动滚动定位）
- 聊天导出为 Markdown
- 日期分隔线
- 长按会话：重命名/删除
- MEMORY.md 编辑器

**系统能力**
- Cron 定时任务（WorkManager 调度 + 通知推送 + 管理 UI）
- Heartbeat 心跳检查
- Agent 模板（5 种预设子代理角色）
- Gateway Watchdog（Node.js 崩溃自动重启）
- Foreground Service（常驻运行）
- 备份/恢复（配置 + 记忆 + 会话 + Skills）
- 配置保护（升级/恢复不丢失 API Key）
- WebViewBridge 浏览器控制（端口 18790）
- Doctor/Selftest API

### 修复
- news_summary 工具改用 RSS/Tavily 多源聚合（不再依赖浏览器 Bridge）
- 客户端 done 事件清理后内容为空的问题
- Android Node.js 环境无 Intl API（改用 getTimezoneOffset）
- Gateway 连接超时（socket.setTimeout + keepAliveTimeout）
- 配置丢失（extractEngine 覆盖 openclaw.json）
- UTF-8 编码损坏（PowerShell replace 破坏特殊字符）
- 深色模式颜色缺失（fab_bg/fab_icon 等）
- web_fetch User-Agent 被服务端拒绝
- 工具超时机制 + max_agent_steps 防死循环

### 移除
- 语音输入功能（fabVoice + RECORD_AUDIO 权限）

---

## v1.0.0 (2026-04-06)

首个发布版本。

- 基础聊天界面（Fragment + RecyclerView + XML）
- Node.js Gateway 内嵌运行
- 多会话管理（创建/切换/重命名）
- 设置页（模型供应商配置、记忆模式、备份恢复、语言切换）
- 文件分析（图片识别）
- 多语言支持（中/英）
- 网络状态监测
