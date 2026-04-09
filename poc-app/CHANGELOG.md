# Changelog

所有重要变更都将记录在此文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

---

## [1.3.0] - 2026-04-09

**设备控制 + 权限管理 + 社区规范完善。**

本版本重点为 App 新增了设备能力接口（相机拍照、GPS 定位、通知读取），重构了运行时权限管理体系，修复了多模态图片 token 估算的严重 Bug，并全面补充了 GitHub 社区健康文件。

### 新增

#### 设备控制 API（DeviceControlApi）
- 新增 \DeviceControlApi.kt\，在 \127.0.0.1:18791\ 端口提供本地 HTTP 接口
- \POST /device/camera/snap\ — 调用系统相机拍照，返回 base64 图片数据
- \GET /device/location\ — 获取当前 GPS 经纬度
- \GET /device/notifications\ — 读取最近系统通知列表
- 配套新增 \CameraCaptureActivity.kt\（透明 Activity，后台触发相机 Intent）
- 配套新增 \PhotoStore.kt\（照片缓冲区，支持异步等待 + 超时保护）

#### 权限管理系统
- 新增 \PermissionManager.kt\，统一管理 5 类运行时权限：
  - 位置 — ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION
  - 相机 — CAMERA
  - 麦克风 — RECORD_AUDIO
  - 通知 — POST_NOTIFICATIONS（Android 13+）
  - 存储 — READ_MEDIA_IMAGES / READ_EXTERNAL_STORAGE（版本自适应）
- \MainActivity\ 首次启动自动请求缺失权限
- 设置页新增权限状态展示，可一键跳转系统设置管理
- \AndroidManifest.xml\ 补充完整权限声明

#### 社区规范文件
- 新增 \LICENSE\（MIT）
- 新增 \CONTRIBUTING.md\（贡献指南，含开发环境搭建、代码规范、PR Checklist）
- 新增 \SECURITY.md\（安全策略，含已知安全限制、最佳实践）
- 新增 \CODE_OF_CONDUCT.md\（Contributor Covenant 2.1 行为准则）
- 新增 \.gitignore\（覆盖 Android/IDE/OS/运行时数据等完整规则）

#### 集成测试
- 新增 \ComprehensiveTest.kt\，内置测试套件覆盖：
  - Gateway API（健康检查、聊天、会话、工具列表）
  - Browser Bridge 连通性
  - Device Control API 可用性
  - 多请求并发压力测试
- 可在设置页一键运行，输出结构化报告

#### Gateway 增强
- Cron 任务持久化存储（\cron_tasks.json\），重启不丢失
- Gateway 版本号升级为 \1.3.0-android\

### 修复

#### 多模态图片 token 估算（严重 Bug）
- **问题**：\	runcateMessages\ 使用 \JSON.stringify(m.content)\ 估算 token，500KB 图片的 base64 编码（~68 万字符）被误算为 ~227,000 tokens，远超 \MAX_CONTEXT_TOKENS\（120,000）导致图片消息被丢弃，LLM 仅收到纯文本 "请分析这张图片"
- **修复**：新增 \estimateMessageTokens()\ 函数，区分 \	ype: 'text'\ 和 \	ype: 'image_url'\，图片按 Vision API 标准固定估算 ~800 tokens/张
- **增强**：截断逻辑强制保留最新用户消息，确保当前轮次内容不会丢失

#### 健康检查生命周期
- 修复 \ChatFragment\ 中 health check 在 \onDestroyView\ 后仍被调度执行的问题（Fix #5/#9）
- 新增 \healthCheckRunning\ 标志位，视图销毁时立即停止调度

#### 会话创建
- \createNewSession()\ 改为 \suspend\ 函数，返回 Boolean 标识成功/失败
- 欢迎语自动保存到 Gateway 服务端，跨会话切换不丢失
- 修复重试后 pending 文件/图片恢复（Fix #7/#8）

#### 设置页
- 权限状态展示集成
- 布局文件优化

### 文档
- 全面重写 \README.md\，新增架构详解、端口分配表、技术栈说明、开发指南、路线图
- 更新 \CHANGELOG.md\ 格式，对齐 Keep a Changelog 规范
- 新增完整社区规范文件（5 个）

### 统计数据
- 新增 5 个 Kotlin 文件
- 修改 13 个文件，+1542 / -700 行
- Gateway 核心代码 4000+ 行

---

## [1.2.0] - 2026-04-08

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

## [1.1.0] - 2026-04-08

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

## [1.0.0] - 2026-04-06

首个发布版本。

### 功能
- 基础聊天界面（Fragment + RecyclerView + XML）
- Node.js Gateway 内嵌运行
- 多会话管理（创建/切换/重命名）
- 设置页（模型供应商配置、记忆模式、备份恢复、语言切换）
- 文件分析（图片识别）
- 多语言支持（中/英）
- 网络状态监测