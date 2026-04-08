# Changelog

All notable changes to this project will be documented in this file.

## [1.1.0] - 2026-04-08

v1.0.0 之后的全面升级，核心 Agent 能力对齐桌面端。

### Agent 核心
- SSE 流式对话（逐字输出，支持 thinking/reasoning）
- 完整的多步工具循环（agentChatStream + streamLLMWithTools）
- 22 个工具：web_search、web_fetch、exec、file_read/write/edit/delete、create_dir、browser 系列、news_summary、skill_search/install、memory_search/store
- 向量记忆（embedding + cosine similarity 语义搜索）
- Skills 自动加载（扫描 skills/ 目录注入 system_prompt）
- MEMORY.md + 日期时间自动注入 system_prompt

### 多模型
- 5 家供应商支持（百炼/OpenAI/Anthropic/DeepSeek/SiliconFlow）
- 模型 Fallback（主模型失败自动切换备用）
- 聊天内快速切换模型
- Token 估算与上下文自动截断

### 用户体验
- 深色模式（完整 Material Design 暗色主题）
- 代码块复制按钮
- Markdown 链接 + 裸链接点击跳转
- 消息搜索（实时高亮 + 自动滚动定位）
- 聊天导出为 Markdown
- 日期分隔线
- MEMORY.md 编辑器

### 系统能力
- Cron 定时任务（WorkManager 调度 + 通知推送 + 管理 UI）
- Heartbeat 心跳检查
- Agent 模板（5 种预设子代理角色）
- Gateway Watchdog（Node.js 崩溃自动重启）
- Foreground Service（常驻运行）
- 备份/恢复（配置 + 记忆 + 会话 + Skills）
- 配置保护（升级/恢复不丢失 API Key）
- WebViewBridge 浏览器控制（端口 18790）

### 修复
- news_summary 改用 RSS/Tavily 多源聚合
- Android 无 Intl API 兼容
- Gateway 连接超时、配置丢失、UTF-8 编码损坏等

### 移除
- 语音输入功能（fabVoice + RECORD_AUDIO 权限）

## [1.0.0] - 2026-04-06

首个发布版本。

- 基础聊天界面（Fragment + RecyclerView + XML）
- Node.js Gateway 内嵌运行
- 多会话管理（创建/切换/重命名）
- 多供应商 LLM 配置（百炼/OpenAI/Anthropic/DeepSeek）
- Markdown 渲染 + 代码高亮
- 图片/文件分析
- Workspace 文件编辑器
- Skills 安装管理
- 备份/恢复
- 双语 UI（中/英）
