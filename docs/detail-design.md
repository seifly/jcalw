# 项目程序流程

## 1. 整体初始化流程

### 1.1 Spring Boot 启动入口
- **类**: `cn.seifly.jclaw.JClawApplication`
- **方法**: `main(String[] args)`
- **行号**: JClawApplication.java:42-44

**主要逻辑**:
1. 调用 `SpringApplication.run()` 启动 Spring Boot 应用
2. 触发 `JClawConfig` 配置类的初始化

---

### 1.2 配置类初始化
- **类**: `cn.seifly.jclaw.config.JClawConfig`
- **方法**: `init()` (带 `@PostConstruct` 注解)
- **行号**: JClawConfig.java:72-102

**主要逻辑**:
1. **加载配置**: 调用 `ConfigLoader.load()` 从 `config.json` 加载配置
2. **默认配置**: 如果没有配置文件，使用 `Config.defaultConfig()` 创建默认配置
3. **合并配置**: 从 `application.yml` 读取配置并合并
4. **配置优先级**: application.yml > .env > ~/.jclaw/config.json > 默认配置

---

### 1.3 Bean 创建顺序
**Spring Bean 创建顺序**（在 `JClawConfig` 中定义）:

1. **Config** (`config()` 方法, JClawConfig.java:386-389)
   - 聚合所有子系统配置

2. **MessageBus** (`messageBus()` 方法, JClawConfig.java:396-402)
   - 消息总线，用于通道间消息传递

3. **AgentRuntime** (`agentRuntime()` 方法, JClawConfig.java:433-476)
   - 核心代理运行时
   - **内部初始化**:
     - 创建 `SkillsLoader`（技能加载器）
     - 创建 `MemoryStore`（记忆存储）
     - 创建 `SessionManager`（会话管理器）
     - 创建 `LLMProvider`（如果配置有效）
     - 创建 `ToolRegistry`（工具注册表）
     - 注册核心工具: ExecTool, ListDirTool, ReadFileTool, WriteFileTool, EditFileTool, WebSearchTool, WebFetchTool, SkillsTool, MessageTool

4. **ChannelManager** (`channelManager()` 方法, JClawConfig.java:415-423)
   - 通道管理器
   - 注入到 AgentRuntime

5. **CronService** (`cronService()` 方法, JClawConfig.java:503-558)
   - 定时任务服务
   - 注册 CronTool 到 AgentRuntime

6. **SkillsLoader** (`skillsLoader()` 方法, JClawConfig.java:569-572)
   - 从 AgentRuntime 获取

7. **TokenUsageStore** (`tokenUsageStore()` 方法, JClawConfig.java:585-593)
   - Token 使用统计存储

---

### 1.4 服务启动
- **类**: `cn.seifly.jclaw.config.JClawConfig`
- **方法**: `startServices()` (带 `@EventListener(ContextRefreshedEvent.class)` 注解)
- **行号**: JClawConfig.java:131-151

**触发时机**: 所有 Bean 创建完成后

**主要逻辑**:
1. **启动 AgentRuntime 消息循环**: 在独立线程中运行 `agentRuntime.run()`
2. **启动 ChannelManager**: 调用 `channelManager.startAll()` 启动所有已配置的通道

---

## 2. Skills 初始化

### 2.1 SkillsLoader 创建
- **类**: `cn.seifly.jclaw.skills.SkillsLoader`
- **构造函数**: `SkillsLoader(String workspace, String globalSkills, String builtinSkills)`
- **行号**: SkillsLoader.java:64-68

**初始化参数**:
- `workspace`: 工作空间根路径
- `workspaceSkills`: `{workspace}/skills`
- `globalSkills`: 全局技能目录

---

### 2.2 技能加载层级
**加载优先级** (SkillsLoader.java:75-92):
1. **workspace** (最高优先级): `{workspace}/skills/`
2. **global**: 全局技能目录
3. **builtin** (最低优先级): classpath 中的内置技能

**内置技能列表** (SkillsLoader.java:48-50):
- `weather`: 天气查询
- `github`: GitHub 操作
- `skill-creator`: 技能创建
- `tmux`: Tmux 操作

---

### 2.3 SkillsSection 上下文构建
- **类**: `cn.seifly.jclaw.agent.context.SkillsSection`
- **方法**: `build(SectionContext context)`
- **行号**: SkillsSection.java:18-32

**主要逻辑**:
1. 调用 `skillsLoader.buildSkillsSummary()` 生成技能摘要
2. 构建技能说明，包括:
   - 已安装技能列表
   - 触发规则
   - 技能自主学习指南

---

## 3. Channel 初始化

### 3.1 ChannelManager 创建
- **类**: `cn.seifly.jclaw.channels.ChannelManager`
- **构造函数**: `ChannelManager(Config config, MessageBus bus)`
- **行号**: ChannelManager.java:56-60

**初始化参数**:
- `config`: 配置对象
- `bus`: 消息总线

---

### 3.2 通道初始化
- **方法**: `initChannels()`
- **行号**: ChannelManager.java:62-77

**初始化的通道类型**:

| 通道类型 | 初始化方法 | 行号 | 配置条件 |
|---------|-----------|------|---------|
| Telegram | `initTelegramChannel()` | ChannelManager.java:82-94 | enabled=true 且 token 非空 |
| Discord | `initDiscordChannel()` | ChannelManager.java:99-111 | enabled=true 且 token 非空 |
| WhatsApp | `initWhatsAppChannel()` | ChannelManager.java:116-128 | enabled=true 且 bridgeUrl 非空 |
| 微信 | `initWechatChannel()` | ChannelManager.java:133-143 | enabled=true |
| 飞书 | `initFeishuChannel()` | ChannelManager.java:148-158 | enabled=true |
| 钉钉 | `initDingTalkChannel()` | ChannelManager.java:163-175 | enabled=true 且 clientId 非空 |
| QQ | `initQQChannel()` | ChannelManager.java:180-190 | enabled=true |
| MaixCam | `initMaixCamChannel()` | ChannelManager.java:195-205 | enabled=true |

---

### 3.3 通道启动
- **方法**: `startAll()`
- **行号**: ChannelManager.java:218-249

**主要逻辑**:
1. 设置 `dispatchRunning = true`
2. 为每个通道启动独立的出站调度线程
3. 依次启动每个已注册的通道

---

## 4. Memory 初始化

### 4.1 MemoryStore 创建
- **类**: `cn.seifly.jclaw.memory.MemoryStore`
- **构造函数**: `MemoryStore(String workspace)`
- **行号**: MemoryStore.java:74-92

**初始化参数**:
- `workspace`: 工作空间根路径

**初始化内容**:
1. **创建目录结构**:
   - `{workspace}/memory/`
   - `{workspace}/memory/topics/`

2. **文件路径**:
   - `indexFile`: `{workspace}/memory/MEMORY.md` (索引层)
   - `memoriesJsonFile`: `{workspace}/memory/MEMORIES.json` (结构化记忆)
   - `archiveJsonFile`: `{workspace}/memory/MEMORIES_ARCHIVE.json` (归档记忆)
   - `topicsDir`: `{workspace}/memory/topics/` (主题文件目录)

3. **加载现有记忆**:
   - 调用 `loadEntries()` 从 `MEMORIES.json` 加载结构化记忆

---

### 4.2 记忆系统架构
**两层记忆存储系统**:

1. **Layer 1 索引层** (MEMORY.md):
   - 始终注入上下文
   - Agent 知道"自己记得什么"
   - ~200 token

2. **Layer 2 内容层**:
   - `topics/*.md`: 按主题组织的知识文件，按需加载
   - `MEMORIES.json`: 带元数据的结构化记忆条目，按评分排序选取

---

### 4.3 MemorySection 上下文构建
- **类**: `cn.seifly.jclaw.agent.context.MemorySection`
- **方法**: `build(SectionContext context)`
- **行号**: MemorySection.java:18-27

**主要逻辑**:
1. 根据上下文窗口计算记忆 token 预算
2. 调用 `memoryStore.getMemoryContext(currentMessage, memoryBudget)` 获取记忆上下文
3. 构建策略:
   - 索引层（MEMORY.md）: 始终注入，不计入预算
   - 主题文件: 按关键词匹配，占 50% 预算
   - 结构化记忆: 按评分排序，占 50% 预算

---

## 5. 初始化流程图

```
SpringApplication.run(JClawApplication.class)
    ↓
@PostConstruct JClawConfig.init()
    ├── ConfigLoader.load() 加载配置
    └── 合并 application.yml 配置
    ↓
Spring Bean 创建顺序
    ├── 1. Config Bean
    ├── 2. MessageBus Bean
    ├── 3. AgentRuntime Bean
    │       ├── 创建 SkillsLoader
    │       ├── 创建 MemoryStore
    │       ├── 创建 SessionManager
    │       ├── 创建 LLMProvider (如配置有效)
    │       ├── 创建 ToolRegistry
    │       └── 注册核心工具
    ├── 4. ChannelManager Bean
    │       └── 初始化所有配置的通道
    ├── 5. CronService Bean
    ├── 6. SkillsLoader Bean
    └── 7. TokenUsageStore Bean
    ↓
@EventListener ContextRefreshedEvent
    ├── 启动 AgentRuntime 消息循环
    └── 启动 ChannelManager 的所有通道
    ↓
系统就绪，等待请求
```

---

# /api/chat/stream 接口主要逻辑流程

## 接口概述
- **接口路径**: `/api/chat/stream`
- **请求方法**: POST
- **请求体格式**: JSON
  ```json
  {
    "message": "你好",
    "sessionId": "web:default",
    "images": ["可选，图片路径列表"]
  }
  ```
- **响应类型**: SSE (Server-Sent Events) 流式响应

## 主要逻辑流程

### 1. 控制器层 (ChatController)

#### 1.1 入口方法: `chatStream`
- **类**: `cn.seifly.jclaw.web.controller.ChatController`
- **方法**: `chatStream(Map<String, Object> request)`
- **行号**: ChatController.java:86-122

**主要逻辑**:
1. 从请求体中解析参数:
   - `message`: 用户消息文本
   - `sessionId`: 会话标识符，默认为 "web:default"
   - `images`: 图片路径列表（多模态支持，可选）

2. 创建 SseEmitter 对象，设置 5 分钟超时时间 (300000ms)

3. 在线程池中异步执行流式处理任务:
   - 调用 `streamAgentResponse` 方法处理请求
   - 处理完成后发送 [DONE] 信号
   - 异常处理：记录日志并发送错误消息

4. 立即返回 SseEmitter 对象，允许客户端开始接收流式响应

#### 1.2 流式响应处理: `streamAgentResponse`
- **类**: `cn.seifly.jclaw.web.controller.ChatController`
- **方法**: `streamAgentResponse(String message, List<String> images, String sessionId, SseEmitter emitter)`
- **行号**: ChatController.java:200-229

**主要逻辑**:
1. 创建 `EnhancedStreamCallback` 回调函数:
   - 当接收到流式事件时，调用 `writeSSEJson` 方法将事件序列化为 JSON 并通过 SSE 发送给客户端

2. 调用 `agentRuntime.processDirectStream` 方法执行核心逻辑:
   - 传入用户消息、图片列表、会话 ID 和回调函数

3. 异常处理:
   - 捕获执行过程中的异常
   - 通过 SSE 发送错误信息给客户端
   - 记录错误日志

### 2. 代理运行时层 (AgentRuntime)

#### 2.1 流式处理入口: `processDirectStream`
- **类**: `cn.seifly.jclaw.agent.AgentRuntime`
- **方法**: `processDirectStream(String content, List<String> images, String sessionKey, LLMProvider.StreamCallback callback)`
- **行号**: AgentRuntime.java:385-411

**主要逻辑**:
1. **配置检查**: 检查 LLM 提供商是否已配置
   - 如果未配置，通过回调发送配置未就绪消息并返回

2. **日志记录**: 记录传入的请求信息

3. **图片路径处理**: 
   - 将相对路径转换为绝对路径，确保 HTTPProvider 能读取到图片文件

4. **构建上下文消息**:
   - 创建 `InboundMessage` 对象
   - 设置媒体信息（图片列表）
   - 调用 `messageRouter.buildContextWithImages` 方法构建包含历史上下文的消息列表

5. **保存用户消息**:
   - 将用户消息（含图片）添加到会话历史
   - 在 LLM 调用前先持久化用户消息，防止异常时丢失

6. **执行 LLM 流式调用**:
   - 获取 `ProviderComponents` 组件
   - 调用 `reActExecutor.executeStream` 方法执行核心的 LLM 迭代循环
   - 传入构建好的消息列表、会话 ID 和流式回调

7. **持久化响应**:
   - 调用 `messageRouter.persistAndSummarize` 方法持久化 AI 响应并进行会话摘要

8. **返回结果**: 返回 LLM 的最终响应内容

### 3. LLM 执行器层 (ReActExecutor)

#### 3.1 流式执行入口: `executeStream`
- **类**: `cn.seifly.jclaw.agent.ReActExecutor`
- **方法**: `executeStream(List<Message> messages, String sessionKey, LLMProvider.StreamCallback callback)`
- **行号**: ReActExecutor.java:169-185

**主要逻辑**:
1. **初始化状态**:
   - 设置当前会话标识 `currentSessionKey`
   - 重置中断标志位 `aborted = false`
   - 设置运行状态标志位 `running = true`
   - 创建增强流式回调 `currentEnhancedCallback`，用于传递给子代理和协同工具

2. **执行核心循环**:
   - 调用 `executeLoop` 方法执行 LLM 迭代循环
   - 传入流式调用器 `callLLMStream` 和流式工具执行器 `executeToolCallsWithStream`

3. **清理状态**:
   - 重置运行状态标志位 `running = false`
   - 清除当前增强流式回调 `currentEnhancedCallback = null`

4. **返回结果**: 返回 LLM 的最终响应内容

#### 3.2 核心迭代循环: `executeLoop`
- **类**: `cn.seifly.jclaw.agent.ReActExecutor`
- **方法**: `executeLoop(List<Message> messages, String sessionKey, LLMProvider.StreamCallback streamCallback, LLMCaller llmCaller, ToolExecutor toolExecutor)`
- **行号**: ReActExecutor.java:200-315

**主要逻辑**:
1. **初始化变量**:
   - `iteration`: 当前迭代次数
   - `finalContent`: 最终响应内容
   - `emptyRetries`: 空响应重试计数
   - `totalAttempts`: 总尝试次数
   - `maxTotalAttempts`: 最大总尝试次数（最大迭代次数 + 空响应重试次数）

2. **迭代循环**:
   - 循环条件: `iteration < maxIterations`
   
   **循环内部逻辑**:
   - **中断检查**: 检查 `aborted` 标志位，如果为 true 则提前退出循环
   - **总尝试次数检查**: 防止无限循环
   - **调用 LLM**: 使用传入的 `llmCaller` 调用 LLM 获取响应
   - **XML 工具调用解析**: 如果 LLM 响应不包含标准工具调用但包含 XML 格式的工具调用，尝试解析
   - **无工具调用处理**:
     - 如果响应内容为空且未超过重试次数，则重试
     - 如果重试耗尽仍为空，使用兜底提示
     - 记录日志并退出循环
   - **有工具调用处理**:
     - 重置空响应重试计数
     - 记录工具调用日志
     - 添加助手消息到对话历史
     - 调用 `toolExecutor.execute` 执行工具调用
     - 保存会话状态，防止多轮迭代中途崩溃丢失进度

3. **循环结束处理**:
   - 如果 `finalContent` 为 null（达到迭代次数限制），使用兜底提示
   - 返回最终响应内容

#### 3.3 流式 LLM 调用: `callLLMStream`
- **类**: `cn.seifly.jclaw.agent.ReActExecutor`
- **方法**: `callLLMStream(List<Message> messages, LLMProvider.StreamCallback callback)`
- **行号**: ReActExecutor.java:349-356

**主要逻辑**:
1. **获取工具定义**: 从 `tools` 注册表获取所有可用工具的定义
2. **构建 LLM 选项**: 设置 `max_tokens` 和 `temperature` 参数
3. **调用 LLM 流式接口**: 调用 `provider.chatStream` 方法执行流式对话
4. **记录 Token 消耗**: 调用 `recordTokenUsage` 方法记录本次调用的 Token 使用情况
5. **返回结果**: 返回 LLM 响应对象

#### 3.4 流式工具调用执行: `executeToolCallsWithStream`
- **类**: `cn.seifly.jclaw.agent.ReActExecutor`
- **方法**: `executeToolCallsWithStream(List<Message> messages, List<ToolCall> toolCalls, String sessionKey, int iteration)`
- **行号**: ReActExecutor.java:471-517

**主要逻辑**:
1. **遍历工具调用列表**: 依次执行每个工具调用

2. **单个工具调用处理**:
   - **日志记录**: 记录工具调用信息（工具名称、迭代次数、参数预览）
   - **发送开始事件**: 通过 `currentEnhancedCallback` 发送工具调用开始事件
   - **执行工具**: 调用 `executeToolCallWithStream` 方法执行工具
   - **判断执行结果**: 检查工具执行是否成功
   - **发送结束事件**: 通过 `currentEnhancedCallback` 发送工具调用结束事件
   - **持久化工具调用记录**: 创建 `ToolCallRecord` 对象并添加到会话记录中，用于历史会话回放
   - **保存结果**: 将工具执行结果添加到对话消息列表和会话历史中

### 4. 流式响应处理

#### 4.1 写入 SSE JSON 事件: `writeSSEJson`
- **类**: `cn.seifly.jclaw.web.controller.ChatController`
- **方法**: `writeSSEJson(SseEmitter emitter, StreamEvent event)`
- **行号**: ChatController.java:234-243

**主要逻辑**:
1. **序列化为 JSON**: 调用 `event.toJson()` 方法将 `StreamEvent` 对象序列化为 JSON 字符串
2. **处理换行符**: 移除 JSON 中的真实换行符，防止 SSE 协议解析错误
3. **发送 SSE 事件**: 使用 `SseEmitter.event()` 构建并发送 SSE 数据事件

#### 4.2 写入 SSE 完成信号: `writeSSEDone`
- **类**: `cn.seifly.jclaw.web.controller.ChatController`
- **方法**: `writeSSEDone(SseEmitter emitter)`
- **行号**: ChatController.java:248-252

**主要逻辑**:
1. 发送 `[DONE]` 信号，标志流式输出结束

#### 4.3 写入 SSE 错误信号: `writeSSEError`
- **类**: `cn.seifly.jclaw.web.controller.ChatController`
- **方法**: `writeSSEError(SseEmitter emitter, String errorMessage)`
- **行号**: ChatController.java:257-261

**主要逻辑**:
1. 转义错误消息中的特殊字符
2. 发送 `[ERROR]` 前缀的错误消息

## 关键组件关系

### 通用流程
```
客户端请求
    ↓
ChatController.chatStream() [入口]
    ↓
ChatController.streamAgentResponse() [流式响应处理]
    ↓
AgentRuntime.processDirectStream() [代理运行时处理]
    ↓
ReActExecutor.executeStream() [LLM 流式执行]
    ↓
ReActExecutor.executeLoop() [核心迭代循环]
    ├── 调用 LLM (callLLMStream)
    ├── 执行工具调用 (executeToolCallsWithStream)
    └── 循环直到获得最终响应
    ↓
SSE 响应返回给客户端
```

### 场景：查询"今天上海的天气怎么样"

#### 关键组件关系
```
用户输入: "今天上海的天气怎么样"
    ↓
ChatController.chatStream() → 接收请求
    ↓
AgentRuntime.processDirectStream() → 处理请求
    ↓
ReActExecutor.executeLoop() [第1次迭代]
    ↓
callLLMStream() → 调用 LLM 分析用户意图
    ↓
LLM 识别到需要查询天气 → 返回工具调用请求 (SkillsTool)
    ↓
executeToolCallsWithStream() → 执行工具调用
    ↓
SkillsTool.execute() → 调用 weather 技能
    ↓
weather 技能 (SKILL.md) → 提供 curl 命令模板
    ↓
ExecTool 或 WebFetchTool → 执行 curl 命令访问 wttr.in
    ↓
wttr.in 服务 → 返回上海天气数据
    ↓
ReActExecutor.executeLoop() [第2次迭代]
    ↓
callLLMStream() → LLM 根据天气数据生成自然语言响应
    ↓
SSE 流式响应 → "今天上海的天气是..."
```

#### 涉及的关键类和方法
| 组件 | 类 | 方法 | 行号 |
|------|-----|------|------|
| 控制器入口 | ChatController | chatStream() | ChatController.java:86-122 |
| 代理运行时 | AgentRuntime | processDirectStream() | AgentRuntime.java:385-411 |
| LLM 执行器 | ReActExecutor | executeLoop() | ReActExecutor.java:200-315 |
| 流式 LLM 调用 | ReActExecutor | callLLMStream() | ReActExecutor.java:349-356 |
| 工具调用执行 | ReActExecutor | executeToolCallsWithStream() | ReActExecutor.java:471-517 |
| 技能工具 | SkillsTool | execute() | SkillsTool.java |
| 天气技能 | - | SKILL.md | skills/weather/SKILL.md |

#### 执行流程说明
1. **意图识别**: LLM 分析用户查询"今天上海的天气怎么样"，识别到需要获取天气信息
2. **工具选择**: LLM 选择调用 `SkillsTool` 来使用内置的 `weather` 技能
3. **技能调用**: `SkillsTool` 加载 `weather` 技能，获取 curl 命令模板
4. **外部 API 调用**: 执行 curl 命令访问 `wttr.in/Shanghai` 获取实时天气数据
5. **结果处理**: LLM 接收天气数据，生成自然语言响应
6. **流式输出**: 通过 SSE 将响应逐字返回给客户端

## 输入输出示例

### 输入
```json
{
  "message": "你好",
  "sessionId": "web:default"
}
```

### 输出 (SSE 流式响应)
```
data: {"type":"content","content":"你"}

data: {"type":"content","content":"好"}

data: {"type":"content","content":"！"}

data: {"type":"content","content":"有"}

data: {"type":"content","content":"什"}

data: {"type":"content","content":"么"}

data: {"type":"content","content":"我"}

data: {"type":"content","content":"可"}

data: {"type":"content","content":"以"}

data: {"type":"content","content":"帮"}

data: {"type":"content","content":"助"}

data: {"type":"content","content":"你"}

data: {"type":"content","content":"的"}

data: {"type":"content","content":"吗"}

data: {"type":"content","content":"？"}

data: [DONE]
```

## 异常处理

### 主要异常场景
1. **LLM 提供商未配置**: 返回配置未就绪消息
2. **LLM 调用失败**: 记录错误日志，通过 SSE 发送错误信息
3. **工具执行失败**: 记录错误信息，继续下一轮迭代
4. **达到最大迭代次数**: 使用兜底提示
5. **空响应**: 重试最多 2 次，仍为空则使用兜底提示
6. **用户中断**: 发送中断消息并提前退出

## 性能优化点

1. **异步处理**: 使用线程池执行流式处理，避免阻塞主线程
2. **增量保存**: 每轮工具调用后保存会话状态，防止数据丢失
3. **上下文截断**: 构建上下文时截断过长消息，避免上下文爆炸
4. **空响应重试**: 对空响应进行有限重试，提高成功率
5. **中断支持**: 支持用户中断长时间运行的任务

## 术语解释
### Tools 就像是泡咖啡所需的“对象”：
1. get_cup (拿杯子)
2. get_coffee_beans (取咖啡豆)
3. grind_beans (研磨咖啡豆)
4. pour_water (倒水)

### Skills 就像是一本《咖啡制作指南》。
这本指南会教你：
1. 先 拿杯子 (get_cup)。
2. 再 取豆 (get_coffee_beans)。
3. 然后 研磨 (grind_beans)。
4. 最后 冲煮 (pour_water)。