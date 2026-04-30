package cn.seifly.jclaw.agentscope;

import io.agentscope.core.model.AnthropicChatModel;
import io.agentscope.core.model.GeminiChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OllamaChatModel;
import io.agentscope.core.model.OpenAIChatModel;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AgentScopeModelManager {
    
    private final Map<String, Model> chatModels = new ConcurrentHashMap<>();
    private final Map<String, ModelConfig> modelConfigs = new ConcurrentHashMap<>();
    private String defaultModelName = "gpt-4o";
    
    public void registerModel(String modelName, String providerName, String apiKey, String apiBase) {
        ModelConfig config = new ModelConfig(modelName, providerName, apiKey, apiBase);
        modelConfigs.put(modelName, config);
        
        Model chatModel = createChatModel(config);
        if (chatModel != null) {
            chatModels.put(modelName, chatModel);
        }
    }
    
    public Model getChatModel(String modelName) {
        if (modelName == null) {
            return chatModels.get(defaultModelName);
        }
        Model model = chatModels.get(modelName);
        if (model == null) {
            return chatModels.get(defaultModelName);
        }
        return model;
    }
    
    public ModelConfig getModelConfig(String modelName) {
        return modelConfigs.get(modelName);
    }
    
    public void setDefaultModel(String modelName) {
        this.defaultModelName = modelName;
    }
    
    public String getDefaultModel() {
        return defaultModelName;
    }
    
    public boolean hasModel(String modelName) {
        return chatModels.containsKey(modelName);
    }
    
    private Model createChatModel(ModelConfig config) {
        return switch (config.providerName().toLowerCase()) {
            case "openai", "openrouter" -> createOpenAiChatModel(config);
            case "dashscope" -> createOpenAiCompatibleChatModel(config);
            case "anthropic" -> createAnthropicChatModel(config);
            case "gemini" -> createGeminiChatModel(config);
            case "ollama" -> createOllamaChatModel(config);
            case "zhipu", "deepseek" -> createOpenAiCompatibleChatModel(config);
            default -> null;
        };
    }
    
    private Model createOpenAiChatModel(ModelConfig config) {
        String baseUrl = normalizeBaseUrl(config.apiBase());
        
        OpenAIChatModel.Builder builder = OpenAIChatModel.builder()
                .apiKey(config.apiKey())
                .modelName(config.modelName());
        
        if (baseUrl != null && !baseUrl.isEmpty()) {
            builder.baseUrl(baseUrl);
        }
        
        return builder.build();
    }
    
    private Model createAnthropicChatModel(ModelConfig config) {
        return AnthropicChatModel.builder()
                .apiKey(config.apiKey())
                .modelName(config.modelName())
                .build();
    }
    
    private Model createGeminiChatModel(ModelConfig config) {
        GeminiChatModel.Builder builder = GeminiChatModel.builder()
                .apiKey(config.apiKey())
                .modelName(config.modelName());
        
        return builder.build();
    }
    
    private Model createOllamaChatModel(ModelConfig config) {
        String baseUrl = normalizeBaseUrl(config.apiBase());
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = "http://localhost:11434";
        }
        
        return OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(config.modelName())
                .build();
    }
    
    private Model createOpenAiCompatibleChatModel(ModelConfig config) {
        return createOpenAiChatModel(config);
    }
    
    public record ModelConfig(
            String modelName,
            String providerName,
            String apiKey,
            String apiBase
    ) {}
    
    private String normalizeBaseUrl(String apiBase) {
        if (apiBase == null || apiBase.isEmpty()) {
            return apiBase;
        }
        
        String normalized = apiBase.trim();
        
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        
        return normalized;
    }
}
