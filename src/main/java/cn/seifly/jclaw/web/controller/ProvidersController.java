package cn.seifly.jclaw.web.controller;

import cn.seifly.jclaw.config.Config;
import cn.seifly.jclaw.config.ProvidersConfig;
import cn.seifly.jclaw.logger.JClawLogger;
import cn.seifly.jclaw.web.WebUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 提供商 API 控制器
 * 
 * 提供 LLM 提供商配置的查询和更新功能。
 */
@RestController
@RequestMapping("/api/providers")
@CrossOrigin(origins = "${jclaw.gateway.cors-origin:*}", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.PUT, RequestMethod.OPTIONS})
public class ProvidersController {
    
    private static final JClawLogger logger = JClawLogger.getLogger("web");
    
    @Autowired
    private Config config;
    
    /**
     * 获取所有 LLM 提供商配置列表
     * 
     * @return 提供商列表，包含 name、apiBase、掩码的 apiKey 以及 authorized 字段
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getProviders() {
        List<Map<String, Object>> providers = new ArrayList<>();
        ProvidersConfig pc = config.getProviders();
        
        addProviderInfo(providers, "openrouter", pc.getOpenrouter());
        addProviderInfo(providers, "openai", pc.getOpenai());
        addProviderInfo(providers, "anthropic", pc.getAnthropic());
        addProviderInfo(providers, "zhipu", pc.getZhipu());
        addProviderInfo(providers, "dashscope", pc.getDashscope());
        addProviderInfo(providers, "gemini", pc.getGemini());
        addProviderInfo(providers, "ollama", pc.getOllama());
        
        return ResponseEntity.ok(providers);
    }
    
    /**
     * 更新指定 LLM 提供商的配置
     * 
     * @param name 提供商名称
     * @param request 包含更新字段的请求体（apiKey、apiBase）
     * @return 更新结果
     */
    @PutMapping("/{name}")
    public ResponseEntity<Map<String, Object>> updateProvider(
            @PathVariable("name") String name,
            @RequestBody Map<String, Object> request) {
        
        boolean success = updateProviderConfig(name, request);
        
        if (success) {
            WebUtils.saveConfig(config, logger);
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "Provider updated");
            
            return ResponseEntity.ok(result);
        } else {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Update failed");
            return ResponseEntity.status(400).body(error);
        }
    }
    
    // ==================== 公共方法（供其他 Controller 使用）====================
    
    /**
     * 根据名称获取 Provider 配置
     */
    public ProvidersConfig.ProviderConfig getProviderByName(String name) {
        ProvidersConfig pc = config.getProviders();
        return switch (name) {
            case "openrouter" -> pc.getOpenrouter();
            case "openai" -> pc.getOpenai();
            case "anthropic" -> pc.getAnthropic();
            case "zhipu" -> pc.getZhipu();
            case "dashscope" -> pc.getDashscope();
            case "gemini" -> pc.getGemini();
            case "ollama" -> pc.getOllama();
            default -> null;
        };
    }
    
    /**
     * 获取当前第一个有效 Provider 名称（按优先级：OpenRouter > DashScope > Zhipu > OpenAI > Anthropic > Gemini > Ollama）。
     */
    public String getCurrentProvider() {
        ProvidersConfig pc = config.getProviders();
        if (isValidProvider(pc.getOpenrouter())) return "openrouter";
        if (isValidProvider(pc.getDashscope())) return "dashscope";
        if (isValidProvider(pc.getZhipu())) return "zhipu";
        if (isValidProvider(pc.getOpenai())) return "openai";
        if (isValidProvider(pc.getAnthropic())) return "anthropic";
        if (isValidProvider(pc.getGemini())) return "gemini";
        if (isValidProvider(pc.getOllama())) return "ollama";
        return "";
    }
    
    // ==================== 私有辅助方法 ====================
    
    /**
     * 向 providers 列表追加一个描述单个 Provider 的 Map，包含 name、apiBase、掩码的 apiKey 以及 authorized 字段。
     */
    private void addProviderInfo(List<Map<String, Object>> providers, String name, ProvidersConfig.ProviderConfig pc) {
        Map<String, Object> provider = new HashMap<>();
        provider.put("name", name);
        provider.put("apiBase", pc.getApiBase() != null
                ? pc.getApiBase() : ProvidersConfig.getDefaultApiBase(name));
        provider.put("apiKey", WebUtils.maskSecret(pc.getApiKey()));
        provider.put("authorized", pc.isValid());
        providers.add(provider);
    }
    
    /**
     * 将请求中的 apiKey/apiBase 写入对应 Provider 配置。
     * 已掩码的 apiKey 不会覆盖原有值。
     * Provider 不存在时返回 false。
     */
    private boolean updateProviderConfig(String name, Map<String, Object> request) {
        ProvidersConfig.ProviderConfig provider = getProviderByName(name);
        if (provider == null) return false;
        
        if (request.containsKey("apiKey")) {
            String apiKey = (String) request.get("apiKey");
            if (!WebUtils.isSecretMasked(apiKey)) {
                provider.setApiKey(apiKey);
            }
        }
        
        if (request.containsKey("apiBase")) {
            provider.setApiBase((String) request.get("apiBase"));
        }
        
        return true;
    }
    
    /**
     * 判断 Provider 配置是否有效（非 null 且通过 isValid 校验）。
     */
    private boolean isValidProvider(ProvidersConfig.ProviderConfig provider) {
        return provider != null && provider.isValid();
    }
}