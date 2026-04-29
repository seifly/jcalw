package cn.seifly.jclaw.springai;

import cn.seifly.jclaw.config.Config;
import cn.seifly.jclaw.config.ProvidersConfig;
import cn.seifly.jclaw.logger.JClawLogger;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class SpringAiProviderFactory {
    
    private static final JClawLogger logger = JClawLogger.getLogger("springai");
    
    private final SpringAiModelManager modelManager;
    private final ToolAdapter toolAdapter;
    
    public SpringAiProviderFactory(SpringAiModelManager modelManager, ToolAdapter toolAdapter) {
        this.modelManager = modelManager;
        this.toolAdapter = toolAdapter;
    }
    
    public void initializeFromConfig(Config config) {
        String defaultModel = config.getAgent().getModel();
        String defaultProvider = config.getAgent().getProvider();
        
        logger.info("Initializing Spring AI models from config", Map.of(
                "defaultModel", defaultModel,
                "defaultProvider", defaultProvider
        ));
        
        registerModelsFromConfig(config);
        
        if (defaultModel != null && modelManager.hasModel(defaultModel)) {
            modelManager.setDefaultModel(defaultModel);
            logger.info("Set default model", Map.of("model", defaultModel));
        }
    }
    
    private void registerModelsFromConfig(Config config) {
        var modelsConfig = config.getModels();
        var providersConfig = config.getProviders();
        
        if (modelsConfig == null || modelsConfig.getDefinitions() == null) {
            logger.warn("No models defined in config");
            return;
        }
        
        for (var entry : modelsConfig.getDefinitions().entrySet()) {
            String modelName = entry.getKey();
            var modelDef = entry.getValue();
            
            String providerName = modelDef.getProvider();
            if (providerName == null) {
                logger.warn("Model has no provider defined, skipping", Map.of("model", modelName));
                continue;
            }
            
            String apiKey = resolveApiKey(providersConfig, providerName);
            String apiBase = resolveApiBase(providersConfig, providerName);
            
            if (apiKey == null && !"ollama".equals(providerName)) {
                logger.warn("Provider has no API key, skipping model", Map.of(
                        "provider", providerName,
                        "model", modelName
                ));
                continue;
            }
            
            modelManager.registerModel(modelName, providerName, apiKey, apiBase);
            logger.info("Registered model with Spring AI", Map.of(
                    "model", modelName,
                    "provider", providerName,
                    "apiBase", apiBase
            ));
        }
    }
    
    private String resolveApiKey(ProvidersConfig providersConfig, String providerName) {
        return switch (providerName.toLowerCase()) {
            case "openai" -> providersConfig.getOpenai() != null 
                    ? providersConfig.getOpenai().getApiKey() : null;
            case "dashscope" -> providersConfig.getDashscope() != null 
                    ? providersConfig.getDashscope().getApiKey() : null;
            case "anthropic" -> providersConfig.getAnthropic() != null 
                    ? providersConfig.getAnthropic().getApiKey() : null;
            case "zhipu" -> providersConfig.getZhipu() != null 
                    ? providersConfig.getZhipu().getApiKey() : null;
            case "gemini" -> providersConfig.getGemini() != null 
                    ? providersConfig.getGemini().getApiKey() : null;
            case "openrouter" -> providersConfig.getOpenrouter() != null 
                    ? providersConfig.getOpenrouter().getApiKey() : null;
            case "ollama" -> "";
            default -> null;
        };
    }
    
    private String resolveApiBase(ProvidersConfig providersConfig, String providerName) {
        return switch (providerName.toLowerCase()) {
            case "openai" -> resolveWithDefault(
                    providersConfig.getOpenai() != null ? providersConfig.getOpenai().getApiBase() : null,
                    "https://api.openai.com/v1"
            );
            case "dashscope" -> resolveWithDefault(
                    providersConfig.getDashscope() != null ? providersConfig.getDashscope().getApiBase() : null,
                    "https://dashscope.aliyuncs.com/compatible-mode/v1"
            );
            case "anthropic" -> resolveWithDefault(
                    providersConfig.getAnthropic() != null ? providersConfig.getAnthropic().getApiBase() : null,
                    "https://api.anthropic.com/v1"
            );
            case "zhipu" -> resolveWithDefault(
                    providersConfig.getZhipu() != null ? providersConfig.getZhipu().getApiBase() : null,
                    "https://open.bigmodel.cn/api/paas/v4"
            );
            case "gemini" -> resolveWithDefault(
                    providersConfig.getGemini() != null ? providersConfig.getGemini().getApiBase() : null,
                    "https://generativelanguage.googleapis.com/v1beta"
            );
            case "openrouter" -> resolveWithDefault(
                    providersConfig.getOpenrouter() != null ? providersConfig.getOpenrouter().getApiBase() : null,
                    "https://openrouter.ai/api/v1"
            );
            case "ollama" -> resolveWithDefault(
                    providersConfig.getOllama() != null ? providersConfig.getOllama().getApiBase() : null,
                    "http://localhost:11434/v1"
            );
            default -> null;
        };
    }
    
    private String resolveWithDefault(String configured, String defaultValue) {
        return (configured != null && !configured.isEmpty()) ? configured : defaultValue;
    }
    
    public SpringAiProvider createProvider(String modelName) {
        if (!modelManager.hasModel(modelName)) {
            throw new IllegalArgumentException("Model not registered: " + modelName);
        }
        
        var modelConfig = modelManager.getModelConfig(modelName);
        return new SpringAiProvider(modelManager, modelName, modelConfig.providerName());
    }
    
    public SpringAiProvider createDefaultProvider() {
        String defaultModel = modelManager.getDefaultModel();
        return createProvider(defaultModel);
    }
    
    public boolean hasModel(String modelName) {
        return modelManager.hasModel(modelName);
    }
    
    public void switchModel(String modelName) {
        if (modelManager.hasModel(modelName)) {
            modelManager.setDefaultModel(modelName);
            logger.info("Switched default model", Map.of("model", modelName));
        } else {
            throw new IllegalArgumentException("Model not registered: " + modelName);
        }
    }
}
