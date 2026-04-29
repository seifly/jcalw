package cn.seifly.jclaw.springai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Service
public class SpringAiChatService {
    
    private final SpringAiModelManager modelManager;
    private final ToolAdapter toolAdapter;
    
    public SpringAiChatService(SpringAiModelManager modelManager, ToolAdapter toolAdapter) {
        this.modelManager = modelManager;
        this.toolAdapter = toolAdapter;
    }
    
    public String chat(List<Message> messages, List<ToolCallback> tools, String modelName, 
                       Double temperature, Integer maxTokens) {
        ChatModel chatModel = getChatModel(modelName);
        ChatClient chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
        
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .messages(messages)
                .tools(tools);
        
        if (temperature != null || maxTokens != null) {
            var builder = ChatOptions.builder();
            if (temperature != null) {
                builder.temperature(temperature);
            }
            if (maxTokens != null) {
                builder.maxTokens(maxTokens);
            }
            spec = spec.options(builder.build());
        }
        
        ChatResponse response = spec.call().chatResponse();
        return extractContent(response);
    }
    
    public String chatStream(List<Message> messages, List<ToolCallback> tools, String modelName,
                             Double temperature, Integer maxTokens,
                             Consumer<String> onChunk, Consumer<ChatResponse> onComplete) {
        ChatModel chatModel = getChatModel(modelName);
        ChatClient chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
        
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .messages(messages)
                .tools(tools);
        
        if (temperature != null || maxTokens != null) {
            var builder = ChatOptions.builder();
            if (temperature != null) {
                builder.temperature(temperature);
            }
            if (maxTokens != null) {
                builder.maxTokens(maxTokens);
            }
            spec = spec.options(builder.build());
        }
        
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
                        if (onChunk != null) {
                            onChunk.accept(content);
                        }
                    }
                }
            }
            finalResponse.set(response);
        }).doOnComplete(() -> {
            if (onComplete != null) {
                onComplete.accept(finalResponse.get());
            }
        }).blockLast();
        
        return fullContent.toString();
    }
    
    public List<Message> convertMessages(List<cn.seifly.jclaw.providers.Message> originalMessages) {
        List<Message> springAiMessages = new ArrayList<>();
        
        for (cn.seifly.jclaw.providers.Message original : originalMessages) {
            springAiMessages.add(convertMessage(original));
        }
        
        return springAiMessages;
    }
    
    private Message convertMessage(cn.seifly.jclaw.providers.Message original) {
        String role = original.getRole();
        String content = original.getContent() != null ? original.getContent() : "";
        
        return switch (role) {
            case "system" -> new SystemMessage(content);
            case "user" -> new UserMessage(content);
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
            default -> new UserMessage(content);
        };
    }
    
    private ChatModel getChatModel(String modelName) {
        ChatModel model = modelManager.getChatModel(modelName);
        if (model == null) {
            throw new IllegalStateException("No chat model available for: " + modelName);
        }
        return model;
    }
    
    private String extractContent(ChatResponse response) {
        if (response == null || response.getResult() == null) {
            return "";
        }
        AssistantMessage message = response.getResult().getOutput();
        return message != null ? message.getText() : "";
    }
}
