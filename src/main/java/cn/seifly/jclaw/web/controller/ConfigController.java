package cn.seifly.jclaw.web.controller;

import cn.seifly.jclaw.agent.AgentRuntime;
import cn.seifly.jclaw.config.AgentConfig;
import cn.seifly.jclaw.config.Config;
import cn.seifly.jclaw.config.ModelsConfig;
import cn.seifly.jclaw.logger.JClawLogger;
import cn.seifly.jclaw.web.WebUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 配置管理 API 控制器
 * 
 * 提供 Agent 及模型配置的查询和更新功能。
 */
@RestController
@RequestMapping("/api/config")
@CrossOrigin(origins = "${jclaw.gateway.cors-origin:*}", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.PUT, RequestMethod.OPTIONS})
public class ConfigController {
    
    private static final JClawLogger logger = JClawLogger.getLogger("web");
    
    @Autowired
    private Config config;
    
    @Autowired
    private AgentRuntime agentRuntime;
    
    @Autowired
    private ProvidersController providersController;
    
    /**
     * 读取当前模型与 Provider 配置
     * 
     * @return 包含 model 和 provider 的配置
     */
    @GetMapping("/model")
    public ResponseEntity<Map<String, Object>> getModelConfig() {
        Map<String, Object> result = new HashMap<>();
        result.put("model", config.getAgent().getModel());
        
        String savedProvider = config.getAgent().getProvider();
        String currentProvider = (savedProvider != null && !savedProvider.isEmpty())
                ? savedProvider : providersController.getCurrentProvider();
        result.put("provider", currentProvider);
        
        return ResponseEntity.ok(result);
    }
    
    /**
     * 更新模型与 Provider 配置并持久化
     * 
     * @param request 包含 model 和 provider 的请求体
     * @return 更新结果
     */
    @PutMapping("/model")
    public ResponseEntity<Map<String, Object>> updateModelConfig(@RequestBody Map<String, Object> request) {
        if (request.containsKey("model")) {
            String newModel = (String) request.get("model");
            config.getAgent().setModel(newModel);
            
            // 切换 model 时，自动从 ModelsConfig 同步对应的 provider，
            // 避免 provider 与 model 手动错配（如 qwen3-max 被发到智谱的 api_base）
            ModelsConfig.ModelDefinition modelDef =
                    config.getModels().getDefinitions().get(newModel);
            if (modelDef != null) {
                config.getAgent().setProvider(modelDef.getProvider());
            }
        }
        
        // 允许显式覆盖 provider（优先级高于 model 自动推断，适用于自定义场景）
        if (request.containsKey("provider")) {
            config.getAgent().setProvider((String) request.get("provider"));
        }
        
        WebUtils.saveConfig(config, logger);
        
        // 配置保存后立即热重载，使模型选择无需重启即可生效
        if (agentRuntime != null) {
            agentRuntime.reloadModel();
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "Model updated");
        
        return ResponseEntity.ok(result);
    }
    
    /**
     * 读取 Agent 全量配置项
     * 
     * @return Agent 配置
     */
    @GetMapping("/agent")
    public ResponseEntity<Map<String, Object>> getAgentConfig() {
        AgentConfig agentConfig = config.getAgent();
        
        Map<String, Object> result = new HashMap<>();
        result.put("workspace", agentConfig.getWorkspace());
        result.put("model", agentConfig.getModel());
        result.put("maxTokens", agentConfig.getMaxTokens());
        result.put("temperature", agentConfig.getTemperature());
        result.put("maxToolIterations", agentConfig.getMaxToolIterations());
        result.put("heartbeatEnabled", agentConfig.isHeartbeatEnabled());
        result.put("restrictToWorkspace", agentConfig.isRestrictToWorkspace());
        
        return ResponseEntity.ok(result);
    }
    
    /**
     * 更新 Agent 配置并持久化
     * 
     * @param request 包含更新字段的请求体
     * @return 更新结果
     */
    @PutMapping("/agent")
    public ResponseEntity<Map<String, Object>> updateAgentConfig(@RequestBody Map<String, Object> request) {
        AgentConfig agentConfig = config.getAgent();
        
        if (request.containsKey("model")) {
            agentConfig.setModel((String) request.get("model"));
        }
        if (request.containsKey("maxTokens")) {
            agentConfig.setMaxTokens((Integer) request.get("maxTokens"));
        }
        if (request.containsKey("temperature")) {
            agentConfig.setTemperature(((Number) request.get("temperature")).doubleValue());
        }
        if (request.containsKey("maxToolIterations")) {
            agentConfig.setMaxToolIterations((Integer) request.get("maxToolIterations"));
        }
        if (request.containsKey("heartbeatEnabled")) {
            agentConfig.setHeartbeatEnabled((Boolean) request.get("heartbeatEnabled"));
        }
        if (request.containsKey("restrictToWorkspace")) {
            agentConfig.setRestrictToWorkspace((Boolean) request.get("restrictToWorkspace"));
        }
        
        WebUtils.saveConfig(config, logger);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "Agent config updated");
        
        return ResponseEntity.ok(result);
    }
}