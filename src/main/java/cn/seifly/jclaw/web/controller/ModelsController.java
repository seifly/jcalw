package cn.seifly.jclaw.web.controller;

import cn.seifly.jclaw.config.Config;
import cn.seifly.jclaw.config.ModelsConfig;
import cn.seifly.jclaw.config.ProvidersConfig;
import cn.seifly.jclaw.logger.JClawLogger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型列表 API 控制器
 * 
 * 提供所有模型定义的查询功能。
 */
@RestController
@RequestMapping("/api/models")
@CrossOrigin(origins = "${jclaw.gateway.cors-origin:*}", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.OPTIONS})
public class ModelsController {
    
    private static final JClawLogger logger = JClawLogger.getLogger("web");
    
    @Autowired
    private Config config;
    
    @Autowired
    private ProvidersController providersController;
    
    /**
     * 获取所有模型定义列表
     * 
     * 每个模型节点会一并附带 authorized 字段，表明对应 Provider 是否已配置 API Key。
     * 
     * @return 模型列表
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getModels() {
        List<Map<String, Object>> models = new ArrayList<>();
        ModelsConfig modelsConfig = config.getModels();

        for (Map.Entry<String, ModelsConfig.ModelDefinition> entry
                : modelsConfig.getDefinitions().entrySet()) {
            String modelName = entry.getKey();
            ModelsConfig.ModelDefinition def = entry.getValue();
            String providerName = def.getProvider();

            ProvidersConfig.ProviderConfig providerConfig =
                    providersController.getProviderByName(providerName);
            boolean authorized = providerConfig != null && providerConfig.isValid();

            Map<String, Object> modelNode = new HashMap<>();
            modelNode.put("name", modelName);
            modelNode.put("provider", providerName);
            modelNode.put("model", def.getModel());
            modelNode.put("maxContextSize",
                    def.getMaxContextSize() != null ? def.getMaxContextSize() : 0);
            modelNode.put("description",
                    def.getDescription() != null ? def.getDescription() : "");
            modelNode.put("authorized", authorized);
            models.add(modelNode);
        }
        
        return ResponseEntity.ok(models);
    }
}