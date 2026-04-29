package cn.seifly.jclaw.springai;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SpringAiModelManager {
    
    private final Map<String, ChatModel> chatModels = new ConcurrentHashMap<>();
    private final Map<String, ModelConfig> modelConfigs = new ConcurrentHashMap<>();
    private String defaultModelName = "gpt-4o";
    
    public void registerModel(String modelName, String providerName, String apiKey, String apiBase) {
        ModelConfig config = new ModelConfig(modelName, providerName, apiKey, apiBase);
        modelConfigs.put(modelName, config);
        
        ChatModel chatModel = createChatModel(config);
        if (chatModel != null) {
            chatModels.put(modelName, chatModel);
        }
    }
    
    public ChatModel getChatModel(String modelName) {
        if (modelName == null) {
            return chatModels.get(defaultModelName);
        }
        ChatModel model = chatModels.get(modelName);
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
    
    private ChatModel createChatModel(ModelConfig config) {
        return switch (config.providerName().toLowerCase()) {
            case "openai", "openrouter" -> createOpenAiChatModel(config);
            case "dashscope", "zhipu", "anthropic", "gemini", "ollama" -> createOpenAiCompatibleChatModel(config);
            default -> null;
        };
    }
    
    private ChatModel createOpenAiChatModel(ModelConfig config) {
        OpenAiApi openAiApi = OpenAiApi.builder()
                .apiKey(config.apiKey())
                .baseUrl(config.apiBase())
                .build();
        
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(config.modelName())
                .build();
        
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .build();
    }
    
    private ChatModel createOpenAiCompatibleChatModel(ModelConfig config) {
        return createOpenAiChatModel(config);
    }
    
    public record ModelConfig(
            String modelName,
            String providerName,
            String apiKey,
            String apiBase
    ) {}
}
