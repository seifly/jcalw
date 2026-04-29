# Spring AI 集成指南

## Agent 架构说明

在您的项目中，Spring AI 与自定义 Agent 架构的集成关系如下：

### 核心组件对照表

| 类别 | 类名 | 说明 |
|------|------|------|
| **自定义 Agent** | `AgentRuntime` | 您项目的主 Agent 引擎 |
| **执行器** | `ReActExecutor` | ReAct 模式执行器 |
| **Spring AI 集成** | `SpringAiProvider` | 使用 Spring AI ChatClient 的适配器 |
| **Spring AI 核心** | `ChatClient` | Spring AI 的对话客户端（非 Agent 类） |

### 关键点

- **Spring AI 框架本身没有专门的 Agent 类**
- **Agent 功能是通过 `ChatClient` + `Tools` + `Advisors` 组合实现的**
- **您的项目有自己的 Agent 架构（`AgentRuntime`），只是使用 Spring AI 作为 LLM 提供者**

## Spring AI 核心概念

### 1. ChatClient - 对话客户端

Spring AI 的核心是对话客户端，用于与大语言模型进行交互：

```java
ChatClient chatClient = ChatClient.builder(chatModel)
    .defaultAdvisors(new SimpleLoggerAdvisor())
    .build();

String response = chatClient.prompt()
    .user("你的问题")
    .call()
    .content();
```

### 2. Tools - 工具调用

通过工具回调机制，让 LLM 能够调用外部工具：

```java
List<ToolCallback> tools = createToolCallbacks(toolDefinitions);

String response = chatClient.prompt()
    .user("执行某个任务")
    .tools(tools)
    .call()
    .content();
```

### 3. Advisors - 建议器

Advisors 提供额外的功能增强，如日志记录、重试等：

```java
ChatClient chatClient = ChatClient.builder(chatModel)
    .defaultAdvisors(
        new SimpleLoggerAdvisor(),
        new RetryAdvisor()
    )
    .build();
```

## 项目中的集成方式

### SpringAiProvider 适配器

您的项目通过 `SpringAiProvider` 将 Spring AI 集成到现有的 LLM 提供者接口中：

```java
@Component
public class SpringAiProvider implements LLMProvider {
    
    private final SpringAiModelManager modelManager;
    
    @Override
    public LLMResponse chat(List<Message> messages, List<ToolDefinition> tools, 
                            String model, Map<String, Object> options) {
        // 获取 Spring AI 的 ChatModel
        ChatModel chatModel = modelManager.getChatModel(model);
        
        // 构建 ChatClient
        ChatClient chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
        
        // 转换消息格式
        List<org.springframework.ai.chat.messages.Message> springAiMessages = 
            convertMessages(messages);
        
        // 执行对话
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .messages(springAiMessages);
        
        // 添加工具支持
        if (tools != null && !tools.isEmpty()) {
            List<ToolCallback> toolCallbacks = createToolCallbacks(tools);
            spec = spec.tools(toolCallbacks);
        }
        
        // 获取响应
        ChatResponse response = spec.call().chatResponse();
        return convertToLLMResponse(response);
    }
}
```

### 消息转换

由于 Spring AI 使用自己的消息格式，需要进行转换：

```java
private org.springframework.ai.chat.messages.Message convertMessage(Message original) {
    String role = original.getRole();
    String content = original.getContent();
    
    return switch (role.toLowerCase()) {
        case "user" -> new UserMessage(content);
        case "assistant" -> new AssistantMessage(content);
        case "system" -> new SystemMessage(content);
        case "tool" -> new ToolResponseMessage(...);
        default -> throw new IllegalArgumentException("Unknown role: " + role);
    };
}
```

## 构建复杂 Agent

如果您想使用 Spring AI 构建更复杂的 Agent，可以参考以下模式：

### 1. 多轮对话 Agent

```java
ChatClient agent = ChatClient.builder(chatModel).build();

List<Message> conversationHistory = new ArrayList<>();

// 第一轮
conversationHistory.add(new UserMessage("第一个问题"));
String response1 = agent.prompt()
    .messages(conversationHistory)
    .call()
    .content();
conversationHistory.add(new AssistantMessage(response1));

// 第二轮
conversationHistory.add(new UserMessage("第二个问题"));
String response2 = agent.prompt()
    .messages(conversationHistory)
    .call()
    .content();
```

### 2. 带工具的 Agent

```java
ChatClient agent = ChatClient.builder(chatModel).build();

// 定义工具
ToolCallback searchTool = new SearchToolCallback();
ToolCallback fileTool = new FileToolCallback();

// 执行带工具的对话
String response = agent.prompt()
    .user("帮我搜索并保存文件")
    .tools(searchTool, fileTool)
    .call()
    .content();
```

### 3. 流式响应 Agent

```java
ChatClient agent = ChatClient.builder(chatModel).build();

Flux<String> stream = agent.prompt()
    .user("生成长文本")
    .stream()
    .content();

stream.subscribe(chunk -> {
    System.out.print(chunk);
});
```

## 配置示例

### application.yml 配置

```yaml
spring:
  ai:
    # OpenAI 配置
    openai:
      api-key: ${OPENAI_API_KEY}
      base-url: https://api.openai.com/v1
      chat:
        options:
          model: gpt-4o
          temperature: 0.7
          max-tokens: 16384
    
    # DashScope (阿里云通义千问) 配置
    dashscope:
      api-key: ${DASHSCOPE_API_KEY}
      base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
      chat:
        options:
          model: qwen-plus
          temperature: 0.7
          max-tokens: 16384
```

### Maven 依赖

```xml
<!-- Spring AI OpenAI -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
    <version>${spring-ai.version}</version>
</dependency>

<!-- Spring AI DashScope -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-dashscope</artifactId>
    <version>${spring-ai.version}</version>
</dependency>
```

## 最佳实践

1. **使用 ChatClient 而非直接调用 ChatModel**
   - ChatClient 提供了更高级的 API
   - 支持链式调用和流畅的编程体验

2. **合理使用 Advisors**
   - `SimpleLoggerAdvisor`: 用于调试和日志记录
   - 可以自定义 Advisor 实现缓存、重试等功能

3. **工具调用的错误处理**
   - 确保工具回调有完善的异常处理
   - 返回友好的错误信息给 LLM

4. **消息格式转换**
   - 在项目内部使用统一的消息格式
   - 在与 Spring AI 交互时进行转换

5. **会话管理**
   - 使用 `SessionManager` 管理对话历史
   - 注意控制上下文长度，避免超出模型限制

## 参考资源

- [Spring AI 官方文档](https://docs.spring.io/spring-ai/reference/)
- [Spring AI ChatClient 指南](https://docs.spring.io/spring-ai/reference/api/chatclient.html)
- [Spring AI Function Calling](https://docs.spring.io/spring-ai/reference/api/tools.html)
