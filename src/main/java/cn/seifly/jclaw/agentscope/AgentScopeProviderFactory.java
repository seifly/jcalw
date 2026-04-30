package cn.seifly.jclaw.agentscope;

import cn.seifly.jclaw.config.Config;
import cn.seifly.jclaw.config.ProvidersConfig;
import cn.seifly.jclaw.logger.JClawLogger;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class AgentScopeProviderFactory {
    
    private static final JClawLogger logger = JClawLogger.getLogger("agentscope");
    
    private final AgentScopeModelManager modelManager;
    
    public AgentScopeProviderFactory(AgentScopeModelManager modelManager) {
        this.modelManager = modelManager;
    }
    
    public void initializeFromConfig(Config config) {
        String defaultModel = config.getAgent().getModel();
        String defaultProvider = config.getAgent().getProvider();
        
        logger.info("Initializing AgentScope models from config", Map.of(
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
            
            var providerConfig = providersConfig.getByName(providerName.toLowerCase());
            
            String apiKey = null;
            String apiBase = null;
            
            if (providerConfig != null) {
                apiKey = providerConfig.getApiKey();
                apiBase = providerConfig.getApiBase();
            }
            
            if (apiBase == null || apiBase.isEmpty()) {
                apiBase = ProvidersConfig.getDefaultApiBase(providerName.toLowerCase());
            }
            
            if (apiKey == null && !"ollama".equals(providerName.toLowerCase())) {
                logger.warn("Provider has no API key, skipping model", Map.of(
                        "provider", providerName,
                        "model", modelName
                ));
                continue;
            }
            
            modelManager.registerModel(modelName, providerName, apiKey, apiBase);
            logger.info("Registered model with AgentScope", Map.of(
                    "model", modelName,
                    "provider", providerName,
                    "apiBase", apiBase
            ));
        }
    }
    
    public AgentScopeProvider createProvider(String modelName) {
        if (!modelManager.hasModel(modelName)) {
            throw new IllegalArgumentException("Model not registered: " + modelName);
        }
        
        var modelConfig = modelManager.getModelConfig(modelName);
        return new AgentScopeProvider(modelManager, modelName, modelConfig.providerName());
    }
    
    public AgentScopeProvider createDefaultProvider() {
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
    
    public AgentScopeModelManager getModelManager() {
        return modelManager;
    }
}
