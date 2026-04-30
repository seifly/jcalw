package cn.seifly.jclaw.agentscope;

import cn.seifly.jclaw.providers.LLMProvider;
import cn.seifly.jclaw.providers.LLMResponse;
import cn.seifly.jclaw.providers.Message;
import cn.seifly.jclaw.providers.ToolCall;
import cn.seifly.jclaw.providers.ToolDefinition;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolResultConverter;
import io.agentscope.core.tool.Toolkit;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class AgentScopeProvider implements LLMProvider {
    
    private final AgentScopeModelManager modelManager;
    private final String defaultModel;
    private final String providerName;
    
    public AgentScopeProvider(AgentScopeModelManager modelManager,
                               String defaultModel, 
                               String providerName) {
        this.modelManager = modelManager;
        this.defaultModel = defaultModel != null ? defaultModel : "gpt-4o";
        this.providerName = providerName != null ? providerName : "unknown";
    }
    
    @Override
    public LLMResponse chat(List<Message> messages, List<ToolDefinition> tools, 
                            String model, Map<String, Object> options) {
        String actualModel = model != null ? model : defaultModel;
        Model chatModel = modelManager.getChatModel(actualModel);
        
        if (chatModel == null) {
            throw new IllegalStateException("No chat model available for: " + actualModel);
        }
        
        Toolkit toolkit = buildToolkit(tools);
        
        ReActAgent.Builder agentBuilder = ReActAgent.builder()
                .name("Assistant")
                .model(chatModel)
                .checkRunning(false);
        
        if (toolkit != null) {
            agentBuilder.toolkit(toolkit);
        }
        
        String systemPrompt = extractSystemPrompt(messages);
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            agentBuilder.sysPrompt(systemPrompt);
        }
        
        ReActAgent agent = agentBuilder.build();
        
        List<Msg> conversationMsgs = convertToConversationMsgs(messages);
        
        if (conversationMsgs.isEmpty()) {
            return LLMResponse.text("");
        }
        
        Mono<Msg> responseMono = agent.call(conversationMsgs.get(conversationMsgs.size() - 1));
        Msg response = responseMono.block();
        
        return convertMsgToLLMResponse(response);
    }
    
    @Override
    public LLMResponse chatStream(List<Message> messages, List<ToolDefinition> tools,
                                  String model, Map<String, Object> options, 
                                  StreamCallback callback) {
        String actualModel = model != null ? model : defaultModel;
        Model chatModel = modelManager.getChatModel(actualModel);
        
        if (chatModel == null) {
            throw new IllegalStateException("No chat model available for: " + actualModel);
        }
        
        Toolkit toolkit = buildToolkit(tools);
        
        ReActAgent.Builder agentBuilder = ReActAgent.builder()
                .name("Assistant")
                .model(chatModel)
                .checkRunning(false);
        
        if (toolkit != null) {
            agentBuilder.toolkit(toolkit);
        }
        
        String systemPrompt = extractSystemPrompt(messages);
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            agentBuilder.sysPrompt(systemPrompt);
        }
        
        ReActAgent agent = agentBuilder.build();
        
        List<Msg> conversationMsgs = convertToConversationMsgs(messages);
        
        if (conversationMsgs.isEmpty()) {
            return LLMResponse.text("");
        }
        
        StringBuilder fullContent = new StringBuilder();
        AtomicReference<Msg> finalResponse = new AtomicReference<>();
        AtomicReference<String> lastContent = new AtomicReference<>("");
        
        Flux<Msg> flux = agent.call(conversationMsgs.get(conversationMsgs.size() - 1)).flux();
        
        flux.doOnNext(msg -> {
            if (msg != null) {
                String currentContent = extractTextFromMsg(msg);
                if (currentContent != null && !currentContent.isEmpty()) {
                    String incremental = getIncrementalContent(lastContent.get(), currentContent);
                    if (incremental != null && !incremental.isEmpty()) {
                        fullContent.append(incremental);
                        if (callback != null) {
                            callback.onChunk(incremental);
                        }
                        lastContent.set(currentContent);
                    }
                }
                finalResponse.set(msg);
            }
        }).blockLast();
        
        Msg response = finalResponse.get();
        if (response == null) {
            return LLMResponse.text(fullContent.toString());
        }
        
        return convertMsgToLLMResponse(response, fullContent.toString());
    }
    
    @Override
    public String getDefaultModel() {
        return defaultModel;
    }
    
    @Override
    public String getName() {
        return providerName;
    }
    
    private String extractSystemPrompt(List<Message> messages) {
        for (Message msg : messages) {
            if ("system".equals(msg.getRole())) {
                return msg.getContent();
            }
        }
        return null;
    }
    
    private List<Msg> convertToConversationMsgs(List<Message> originalMessages) {
        List<Msg> agentScopeMsgs = new ArrayList<>();
        
        for (Message original : originalMessages) {
            if ("system".equals(original.getRole())) {
                continue;
            }
            agentScopeMsgs.add(convertSingleMessage(original));
        }
        
        return agentScopeMsgs;
    }
    
    private Msg convertSingleMessage(Message original) {
        String role = original.getRole();
        String content = original.getContent() != null ? original.getContent() : "";
        
        return switch (role) {
            case "user" -> {
                Msg.Builder builder = Msg.builder()
                        .name("user")
                        .role(MsgRole.USER);
                
                if (original.hasImages()) {
                    builder.textContent(content);
                } else {
                    builder.textContent(content);
                }
                yield builder.build();
            }
            case "assistant" -> {
                Msg.Builder builder = Msg.builder()
                        .name("assistant")
                        .role(MsgRole.ASSISTANT);
                
                if (original.getToolCalls() != null && !original.getToolCalls().isEmpty()) {
                    if (content != null && !content.isEmpty()) {
                        builder.textContent(content);
                    }
                    for (ToolCall toolCall : original.getToolCalls()) {
                        builder.textContent(String.format("[ToolCall: %s(%s)]", 
                                toolCall.getName(), toolCall.getArguments()));
                    }
                } else {
                    builder.textContent(content);
                }
                yield builder.build();
            }
            case "tool" -> Msg.builder()
                    .name("tool")
                    .role(MsgRole.TOOL)
                    .textContent(content)
                    .build();
            default -> Msg.builder()
                    .name("user")
                    .role(MsgRole.USER)
                    .textContent(content)
                    .build();
        };
    }
    
    private Toolkit buildToolkit(List<ToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) {
            return null;
        }
        
        Toolkit toolkit = new Toolkit();
        for (ToolDefinition tool : tools) {
            toolkit.registerTool(convertToolDefinition(tool));
        }
        return toolkit;
    }
    
    private Tool convertToolDefinition(ToolDefinition toolDef) {
        String name = toolDef.getName();
        String description = toolDef.getDescription();
        Map<String, Object> parameters = toolDef.getParameters();
        
        return new Tool() {
            @Override
            public Class<? extends Annotation> annotationType() {
                return null;
            }

            @Override
            public String name() {
                return "";
            }

            @Override
            public String description() {
                return "";
            }

            @Override
            public Class<? extends ToolResultConverter> converter() {
                return null;
            }


        };
    }
    
    private String extractTextFromMsg(Msg msg) {
        if (msg == null) {
            return "";
        }
        
        String textContent = msg.getTextContent();
        return textContent != null ? textContent : "";
    }
    
    private String getIncrementalContent(String lastContent, String currentContent) {
        if (currentContent == null || currentContent.isEmpty()) {
            return "";
        }
        
        if (lastContent == null || lastContent.isEmpty()) {
            return currentContent;
        }
        
        if (currentContent.startsWith(lastContent)) {
            return currentContent.substring(lastContent.length());
        }
        
        return currentContent;
    }
    
    private LLMResponse convertMsgToLLMResponse(Msg msg) {
        return convertMsgToLLMResponse(msg, null);
    }
    
    private LLMResponse convertMsgToLLMResponse(Msg msg, String fallbackContent) {
        if (msg == null) {
            return LLMResponse.text(fallbackContent != null ? fallbackContent : "");
        }
        
        String content = extractTextFromMsg(msg);
        if ((content == null || content.isEmpty()) && fallbackContent != null) {
            content = fallbackContent;
        }
        
        LLMResponse llmResponse = new LLMResponse();
        llmResponse.setContent(content != null ? content : "");
        
        llmResponse.setFinishReason("stop");
        
        return llmResponse;
    }
}
