package cn.seifly.jclaw.web.controller;

import cn.seifly.jclaw.JClawApplication;
import cn.seifly.jclaw.config.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 健康检查和系统信息控制器
 * 
 * 提供Spring Boot Web模式下的基本系统信息和健康检查端点。
 */
@RestController
@RequestMapping("/api")
public class HealthController {
    
    @Autowired(required = false)
    private Config config;
    
    /**
     * 健康检查端点
     * 
     * @return 健康状态信息
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "UP");
        result.put("timestamp", LocalDateTime.now().toString());
        result.put("version", JClawApplication.VERSION);
        return result;
    }
    
    /**
     * 系统信息端点
     * 
     * @return 系统基本信息
     */
    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> result = new HashMap<>();
        result.put("name", "jclaw");
        result.put("version", JClawApplication.VERSION);
        result.put("description", "超轻量个人AI助手");
        result.put("timestamp", LocalDateTime.now().toString());
        
        if (config != null) {
            Map<String, Object> configInfo = new HashMap<>();
            configInfo.put("workspace", config.getWorkspacePath());
            configInfo.put("model", config.getAgent().getModel());
            configInfo.put("maxTokens", config.getAgent().getMaxTokens());
            configInfo.put("temperature", config.getAgent().getTemperature());
            result.put("config", configInfo);
        }
        
        return result;
    }
}