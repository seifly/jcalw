package cn.seifly.jclaw.springai;

import cn.seifly.jclaw.providers.LLMProvider;
import cn.seifly.jclaw.providers.LLMResponse;
import cn.seifly.jclaw.providers.Message;
import cn.seifly.jclaw.providers.ToolCall;
import cn.seifly.jclaw.providers.ToolDefinition;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class SpringAiProvider implements LLMProvider {
    
    private final SpringAiModelManager modelManager;
    private final String defaultModel;
    private final String providerName;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    public SpringAiProvider(SpringAiModelManager modelManager,
                            String defaultModel, String providerName) {
        this.modelManager = modelManager;
        this.defaultModel = defaultModel != null ? defaultModel : "gpt-4o";
        this.providerName = providerName != null ? providerName : "unknown";
    }
    
    @Override
    public LLMResponse chat(List<Message> messages, List<ToolDefinition> tools, 
                            String model, Map<String, Object> options) {
        String actualModel = model != null ? model : defaultModel;
        List<org.springframework.ai.chat.messages.Message> springAiMessages = convertMessages(messages);
        
        ChatClient chatClient = ChatClient.builder(modelManager.getChatModel(actualModel))
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
        
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .messages(springAiMessages);
        
        if (tools != null && !tools.isEmpty()) {
            List<ToolCallback> toolCallbacks = createToolCallbacks(tools);
            spec = spec.toolCallbacks(toolCallbacks);
        }
        
        spec = applyOptions(spec, options);
        
        ChatResponse response = spec.call().chatResponse();
        return convertToLLMResponse(response);
    }
    
    @Override
    public LLMResponse chatStream(List<Message> messages, List<ToolDefinition> tools,
                                  String model, Map<String, Object> options, 
                                  StreamCallback callback) {
        String actualModel = model != null ? model : defaultModel;
        List<org.springframework.ai.chat.messages.Message> springAiMessages = convertMessages(messages);
        
        ChatClient chatClient = ChatClient.builder(modelManager.getChatModel(actualModel))
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
        
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .messages(springAiMessages);
        
        if (tools != null && !tools.isEmpty()) {
            List<ToolCallback> toolCallbacks = createToolCallbacks(tools);
            spec = spec.toolCallbacks(toolCallbacks);
        }
        
        spec = applyOptions(spec, options);
        
        StringBuilder fullContent = new StringBuilder();
        AtomicReference<ChatResponse> finalResponse = new AtomicReference<>();
        
        Flux<ChatResponse> flux = spec.stream().chatResponse();
        
        flux.doOnNext(response -> {
            if (response != null && response.getResult() != null) {
                AssistantMessage assistantMessage = response.getResult().getOutput();
                if (assistantMessage != null && assistantMessage.getText() != null) {
                    String content = assistantMessage.getText();
                    if (!content.isEmpty()) {
                        fullContent.append(content);
                        if (callback != null) {
                            callback.onChunk(content);
                        }
                    }
                }
            }
            finalResponse.set(response);
        }).blockLast();
        
        ChatResponse response = finalResponse.get();
        if (response == null) {
            return LLMResponse.text(fullContent.toString());
        }
        
        return convertToLLMResponse(response, fullContent.toString());
    }
    
    @Override
    public String getDefaultModel() {
        return defaultModel;
    }
    
    @Override
    public String getName() {
        return providerName;
    }
    
    private List<ToolCallback> createToolCallbacks(List<ToolDefinition> tools) {
        List<ToolCallback> callbacks = new ArrayList<>();
        
        for (ToolDefinition tool : tools) {
            var springAiToolDef = org.springframework.ai.tool.definition.ToolDefinition.builder()
                    .name(tool.getName())
                    .description(tool.getDescription())
                    .inputSchema(convertParametersToJson(tool.getParameters()))
                    .build();
            
            callbacks.add(new NoOpToolCallback(springAiToolDef));
        }
        
        return callbacks;
    }
    
    private String convertParametersToJson(Map<String, Object> originalParams) {
        if (originalParams == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(originalParams);
        } catch (Exception e) {
            return "{}";
        }
    }
    
    private ChatClient.ChatClientRequestSpec applyOptions(ChatClient.ChatClientRequestSpec spec, 
                                                          Map<String, Object> options) {
        if (options == null || options.isEmpty()) {
            return spec;
        }
        
        var builder = ChatOptions.builder();
        boolean hasOptions = false;
        
        if (options.containsKey("temperature")) {
            Object temp = options.get("temperature");
            if (temp instanceof Number) {
                builder.temperature(((Number) temp).doubleValue());
                hasOptions = true;
            }
        }
        
        if (options.containsKey("max_tokens")) {
            Object maxTokens = options.get("max_tokens");
            if (maxTokens instanceof Number) {
                builder.maxTokens(((Number) maxTokens).intValue());
                hasOptions = true;
            }
        }
        
        if (hasOptions) {
            spec = spec.options(builder.build());
        }
        
        return spec;
    }
    
    private List<org.springframework.ai.chat.messages.Message> convertMessages(List<Message> originalMessages) {
        List<org.springframework.ai.chat.messages.Message> springAiMessages = new ArrayList<>();
        
        for (Message original : originalMessages) {
            springAiMessages.add(convertMessage(original));
        }
        
        return springAiMessages;
    }
    
    private org.springframework.ai.chat.messages.Message convertMessage(Message original) {
        String role = original.getRole();
        String content = original.getContent() != null ? original.getContent() : "";
        
        return switch (role) {
            case "system" -> new org.springframework.ai.chat.messages.SystemMessage(content);
            case "user" -> new org.springframework.ai.chat.messages.UserMessage(content);
            case "assistant" -> {
                AssistantMessage message = new AssistantMessage(content);
                yield message;
            }
            case "tool" -> {
                String toolCallId = original.getToolCallId() != null ? original.getToolCallId() : "";
                String toolName = original.getToolName() != null ? original.getToolName() : "";
                ToolResponseMessage.ToolResponse toolResponse = 
                        new ToolResponseMessage.ToolResponse(toolCallId, toolName, content);
                yield ToolResponseMessage.builder()
                        .responses(List.of(toolResponse))
                        .build();
            }
            default -> new org.springframework.ai.chat.messages.UserMessage(content);
        };
    }
    
    private LLMResponse convertToLLMResponse(ChatResponse response) {
        return convertToLLMResponse(response, null);
    }
    
    private LLMResponse convertToLLMResponse(ChatResponse response, String fallbackContent) {
        if (response == null) {
            return LLMResponse.text(fallbackContent != null ? fallbackContent : "");
        }
        
        Generation result = response.getResult();
        if (result == null) {
            return LLMResponse.text(fallbackContent != null ? fallbackContent : "");
        }
        
        AssistantMessage message = result.getOutput();
        if (message == null) {
            return LLMResponse.text(fallbackContent != null ? fallbackContent : "");
        }
        
        String content = message.getText();
        if (content == null || content.isEmpty()) {
            content = fallbackContent;
        }
        
        LLMResponse llmResponse = new LLMResponse();
        llmResponse.setContent(content != null ? content : "");
        
        List<ToolCall> toolCalls = extractToolCalls(message);
        if (!toolCalls.isEmpty()) {
            llmResponse.setToolCalls(toolCalls);
        }
        
        llmResponse.setFinishReason("stop");
        
        return llmResponse;
    }
    
    private List<ToolCall> extractToolCalls(AssistantMessage message) {
        List<ToolCall> toolCalls = new ArrayList<>();
        
        if (message.getToolCalls() == null || message.getToolCalls().isEmpty()) {
            return toolCalls;
        }
        
        for (var springAiToolCall : message.getToolCalls()) {
            String name = springAiToolCall.name();
            String arguments = springAiToolCall.arguments();
            
            Map<String, Object> argsMap = parseArguments(arguments);
            
            ToolCall toolCall = new ToolCall(
                    springAiToolCall.id() != null ? springAiToolCall.id() : "0",
                    name,
                    argsMap
            );
            toolCall.setType("function");
            toolCalls.add(toolCall);
        }
        
        return toolCalls;
    }
    
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(String arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return new HashMap<>();
        }
        
        try {
            return objectMapper.readValue(arguments, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new HashMap<>();
        }
    }
    
    public static class NoOpToolCallback implements ToolCallback {
        private final org.springframework.ai.tool.definition.ToolDefinition toolDefinition;
        
        public NoOpToolCallback(org.springframework.ai.tool.definition.ToolDefinition toolDefinition) {
            this.toolDefinition = toolDefinition;
        }
        
        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return toolDefinition;
        }
        
        @Override
        public String call(String toolInput) {
            return "";
        }
    }
}
