package cn.seifly.jclaw.web.controller;

import cn.seifly.jclaw.config.Config;
import cn.seifly.jclaw.config.MCPServersConfig;
import cn.seifly.jclaw.logger.JClawLogger;
import cn.seifly.jclaw.mcp.MCPClient;
import cn.seifly.jclaw.mcp.MCPMessage;
import cn.seifly.jclaw.mcp.SSEMCPClient;
import cn.seifly.jclaw.mcp.StreamableHttpMCPClient;
import cn.seifly.jclaw.mcp.StdioMCPClient;
import cn.seifly.jclaw.web.WebUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 服务器配置管理 API 控制器
 * 
 * 支持的操作：
 * - GET  /api/mcp          获取 MCP 配置（enabled 状态 + 服务器列表）
 * - PUT  /api/mcp          更新 MCP 全局开关（enabled）
 * - POST /api/mcp          添加新的 MCP 服务器配置
 * - PUT  /api/mcp/{name}   更新指定 MCP 服务器配置
 * - DELETE /api/mcp/{name} 删除指定 MCP 服务器配置
 * - POST /api/mcp/{name}/test 测试连接并获取工具列表
 */
@RestController
@RequestMapping("/api/mcp")
@CrossOrigin(origins = "${jclaw.gateway.cors-origin:*}", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS})
public class MCPController {
    
    private static final JClawLogger logger = JClawLogger.getLogger("web");
    
    @Autowired
    private Config config;
    
    /**
     * 获取 MCP 配置（enabled 状态 + 服务器列表）
     * 
     * @return MCP 配置
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getConfig() {
        MCPServersConfig mcpConfig = getOrCreateMcpConfig();
        
        Map<String, Object> result = new HashMap<>();
        result.put("enabled", mcpConfig.isEnabled());
        
        List<Map<String, Object>> serversArray = new ArrayList<>();
        for (MCPServersConfig.MCPServerConfig server : mcpConfig.getServers()) {
            Map<String, Object> serverNode = new HashMap<>();
            serverNode.put("name", server.getName());
            serverNode.put("type", server.getType() != null ? server.getType() : "sse");
            serverNode.put("description", server.getDescription() != null ? server.getDescription() : "");
            serverNode.put("endpoint", server.getEndpoint() != null ? server.getEndpoint() : "");
            serverNode.put("apiKey", server.getApiKey() != null ? WebUtils.maskSecret(server.getApiKey()) : "");
            serverNode.put("command", server.getCommand() != null ? server.getCommand() : "");
            
            if (server.getArgs() != null) {
                serverNode.put("args", new ArrayList<>(server.getArgs()));
            }
            
            if (server.getEnv() != null) {
                serverNode.put("env", new LinkedHashMap<>(server.getEnv()));
            }
            
            serverNode.put("enabled", server.isEnabled());
            serverNode.put("timeout", server.getTimeout());
            
            serversArray.add(serverNode);
        }
        
        result.put("servers", serversArray);
        
        return ResponseEntity.ok(result);
    }
    
    /**
     * 更新 MCP 全局开关（enabled）
     * 
     * @param request 包含 enabled 字段的请求体
     * @return 更新结果
     */
    @PutMapping
    public ResponseEntity<Map<String, Object>> updateEnabled(@RequestBody Map<String, Object> request) {
        MCPServersConfig mcpConfig = getOrCreateMcpConfig();
        
        if (request.containsKey("enabled")) {
            mcpConfig.setEnabled((Boolean) request.get("enabled"));
        }
        
        WebUtils.saveConfig(config, logger);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "MCP config updated");
        
        return ResponseEntity.ok(result);
    }
    
    /**
     * 添加新的 MCP 服务器配置
     * 
     * @param request 包含服务器配置的请求体
     * @return 创建结果
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> addServer(@RequestBody Map<String, Object> request) {
        String name = (String) request.getOrDefault("name", "");
        
        if (name == null || name.isEmpty()) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Server name is required");
            return ResponseEntity.status(400).body(error);
        }
        
        MCPServersConfig mcpConfig = getOrCreateMcpConfig();
        
        // 检查名称是否已存在
        boolean exists = mcpConfig.getServers().stream()
                .anyMatch(s -> s.getName().equals(name));
        
        if (exists) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Server '" + name + "' already exists");
            return ResponseEntity.status(409).body(error);
        }
        
        MCPServersConfig.MCPServerConfig serverConfig = new MCPServersConfig.MCPServerConfig();
        serverConfig.setName(name);
        serverConfig.setType((String) request.getOrDefault("type", "sse"));
        serverConfig.setDescription((String) request.getOrDefault("description", ""));
        serverConfig.setEndpoint((String) request.getOrDefault("endpoint", ""));
        serverConfig.setApiKey((String) request.getOrDefault("apiKey", ""));
        serverConfig.setCommand((String) request.getOrDefault("command", ""));
        
        if (request.containsKey("args") && request.get("args") instanceof List<?>) {
            @SuppressWarnings("unchecked")
            List<String> argsList = (List<String>) request.get("args");
            serverConfig.setArgs(new ArrayList<>(argsList));
        }
        
        if (request.containsKey("env") && request.get("env") instanceof Map<?, ?>) {
            @SuppressWarnings("unchecked")
            Map<String, String> envMap = (Map<String, String>) request.get("env");
            serverConfig.setEnv(new LinkedHashMap<>(envMap));
        }
        
        serverConfig.setEnabled((Boolean) request.getOrDefault("enabled", true));
        serverConfig.setTimeout((Integer) request.getOrDefault("timeout", 30000));
        
        mcpConfig.getServers().add(serverConfig);
        WebUtils.saveConfig(config, logger);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "Server '" + name + "' added");
        
        return ResponseEntity.status(201).body(result);
    }
    
    /**
     * 更新指定 MCP 服务器配置
     * 
     * @param name 服务器名称（URL 编码）
     * @param request 包含更新字段的请求体
     * @return 更新结果
     */
    @PutMapping("/{name}")
    public ResponseEntity<Map<String, Object>> updateServer(
            @PathVariable("name") String name,
            @RequestBody Map<String, Object> request) {
        
        try {
            String serverName = URLDecoder.decode(name, StandardCharsets.UTF_8);
            MCPServersConfig mcpConfig = getOrCreateMcpConfig();
            MCPServersConfig.MCPServerConfig serverConfig = findServer(mcpConfig, serverName);
            
            if (serverConfig == null) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Server '" + serverName + "' not found");
                return ResponseEntity.status(404).body(error);
            }
            
            if (request.containsKey("type")) {
                serverConfig.setType((String) request.get("type"));
            }
            if (request.containsKey("description")) {
                serverConfig.setDescription((String) request.get("description"));
            }
            if (request.containsKey("endpoint")) {
                serverConfig.setEndpoint((String) request.get("endpoint"));
            }
            if (request.containsKey("apiKey")) {
                String apiKey = (String) request.get("apiKey");
                if (!WebUtils.isSecretMasked(apiKey)) {
                    serverConfig.setApiKey(apiKey);
                }
            }
            if (request.containsKey("command")) {
                serverConfig.setCommand((String) request.get("command"));
            }
            if (request.containsKey("args") && request.get("args") instanceof List<?>) {
                @SuppressWarnings("unchecked")
                List<String> argsList = (List<String>) request.get("args");
                serverConfig.setArgs(new ArrayList<>(argsList));
            }
            if (request.containsKey("env") && request.get("env") instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked")
                Map<String, String> envMap = (Map<String, String>) request.get("env");
                serverConfig.setEnv(new LinkedHashMap<>(envMap));
            }
            if (request.containsKey("enabled")) {
                serverConfig.setEnabled((Boolean) request.get("enabled"));
            }
            if (request.containsKey("timeout")) {
                serverConfig.setTimeout((Integer) request.get("timeout"));
            }
            
            WebUtils.saveConfig(config, logger);
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "Server '" + serverName + "' updated");
            
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            logger.error("MCP API error", Map.of("error", e.getMessage()));
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }
    
    /**
     * 删除指定 MCP 服务器配置
     * 
     * @param name 服务器名称（URL 编码）
     * @return 删除结果
     */
    @DeleteMapping("/{name}")
    public ResponseEntity<Map<String, Object>> deleteServer(@PathVariable("name") String name) {
        try {
            String serverName = URLDecoder.decode(name, StandardCharsets.UTF_8);
            MCPServersConfig mcpConfig = getOrCreateMcpConfig();
            
            boolean removed = mcpConfig.getServers().removeIf(s -> s.getName().equals(serverName));
            
            if (!removed) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Server '" + serverName + "' not found");
                return ResponseEntity.status(404).body(error);
            }
            
            WebUtils.saveConfig(config, logger);
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "Server '" + serverName + "' deleted");
            
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            logger.error("MCP API error", Map.of("error", e.getMessage()));
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }
    
    /**
     * 测试连接并获取工具列表
     * 
     * @param name 服务器名称（URL 编码）
     * @return 测试结果，包含连接状态、服务器信息和工具列表
     */
    @PostMapping("/{name}/test")
    public ResponseEntity<Map<String, Object>> testConnection(@PathVariable("name") String name) {
        try {
            String serverName = URLDecoder.decode(name, StandardCharsets.UTF_8);
            MCPServersConfig mcpConfig = getOrCreateMcpConfig();
            MCPServersConfig.MCPServerConfig serverConfig = findServer(mcpConfig, serverName);
            
            if (serverConfig == null) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Server '" + serverName + "' not found");
                return ResponseEntity.status(404).body(error);
            }
            
            Map<String, Object> result = new HashMap<>();
            result.put("serverName", serverName);
            
            MCPClient client = null;
            try {
                // 根据类型创建临时客户端
                if (serverConfig.isStdio()) {
                    client = new StdioMCPClient(
                            serverConfig.getCommand(),
                            serverConfig.getArgs(),
                            serverConfig.getEnv(),
                            serverConfig.getTimeout()
                    );
                } else if (serverConfig.isStreamableHttp()) {
                    client = new StreamableHttpMCPClient(
                            serverConfig.getEndpoint(),
                            serverConfig.getApiKey(),
                            serverConfig.getTimeout()
                    );
                } else {
                    client = new SSEMCPClient(
                            serverConfig.getEndpoint(),
                            serverConfig.getApiKey(),
                            serverConfig.getTimeout()
                    );
                }
                
                // 连接
                client.connect();
                result.put("connected", true);
                
                // 初始化握手
                Map<String, Object> initParams = new HashMap<>();
                initParams.put("protocolVersion", "2024-11-05");
                initParams.put("capabilities", Collections.emptyMap());
                initParams.put("clientInfo", Map.of("name", "jclaw", "version", "0.1.0"));
                
                MCPMessage initResponse = client.sendRequest("initialize", initParams);
                result.put("initialized", true);
                
                // 解析服务器信息
                if (initResponse.getResult() != null) {
                    Map<String, Object> initResult = initResponse.getResult();
                    Map<String, Object> serverInfoNode = new HashMap<>();
                    
                    if (initResult.get("protocolVersion") != null) {
                        serverInfoNode.put("protocolVersion", (String) initResult.get("protocolVersion"));
                    }
                    
                    @SuppressWarnings("unchecked")
                    Map<String, Object> serverInfo = (Map<String, Object>) initResult.get("serverInfo");
                    if (serverInfo != null) {
                        if (serverInfo.get("name") != null) {
                            serverInfoNode.put("name", (String) serverInfo.get("name"));
                        }
                        if (serverInfo.get("version") != null) {
                            serverInfoNode.put("version", (String) serverInfo.get("version"));
                        }
                    }
                    
                    result.put("serverInfo", serverInfoNode);
                }
                
                // 发送 initialized 通知
                client.sendNotification("notifications/initialized", Collections.emptyMap());
                
                // 获取工具列表
                MCPMessage toolsResponse = client.sendRequest("tools/list", Collections.emptyMap());
                
                List<Map<String, Object>> toolsArray = new ArrayList<>();
                if (toolsResponse.getResult() != null && toolsResponse.getResult().containsKey("tools")) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> toolsList =
                            (List<Map<String, Object>>) toolsResponse.getResult().get("tools");
                    
                    if (toolsList != null) {
                        for (Map<String, Object> tool : toolsList) {
                            Map<String, Object> toolNode = new HashMap<>();
                            toolNode.put("name", (String) tool.get("name"));
                            toolNode.put("description", tool.get("description") != null ? (String) tool.get("description") : "");
                            
                            // 解析参数信息
                            @SuppressWarnings("unchecked")
                            Map<String, Object> inputSchema = (Map<String, Object>) tool.get("inputSchema");
                            if (inputSchema != null) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> properties = (Map<String, Object>) inputSchema.get("properties");
                                
                                if (properties != null && !properties.isEmpty()) {
                                    List<Map<String, Object>> paramsArray = new ArrayList<>();
                                    for (String paramName : properties.keySet()) {
                                        Map<String, Object> paramNode = new HashMap<>();
                                        paramNode.put("name", paramName);
                                        
                                        @SuppressWarnings("unchecked")
                                        Map<String, Object> paramDef = (Map<String, Object>) properties.get(paramName);
                                        if (paramDef != null) {
                                            if (paramDef.get("type") != null) {
                                                paramNode.put("type", (String) paramDef.get("type"));
                                            }
                                            if (paramDef.get("description") != null) {
                                                paramNode.put("description", (String) paramDef.get("description"));
                                            }
                                        }
                                        paramsArray.add(paramNode);
                                    }
                                    toolNode.put("parameters", paramsArray);
                                }
                                
                                // 解析 required 字段
                                @SuppressWarnings("unchecked")
                                List<String> required = (List<String>) inputSchema.get("required");
                                if (required != null) {
                                    toolNode.put("required", new ArrayList<>(required));
                                }
                            }
                            
                            toolsArray.add(toolNode);
                        }
                    }
                }
                
                result.put("tools", toolsArray);
                result.put("toolCount", toolsArray.size());
                result.put("success", true);
                
                return ResponseEntity.ok(result);
                
            } catch (Exception e) {
                logger.error("MCP test connection failed", Map.of(
                        "server", serverName, "error", e.getMessage()));
                result.put("connected", false);
                result.put("success", false);
                result.put("error", e.getMessage());
                return ResponseEntity.ok(result);
            } finally {
                if (client != null) {
                    try {
                        client.close();
                    } catch (Exception ignored) {
                    }
                }
            }
            
        } catch (Exception e) {
            logger.error("MCP API error", Map.of("error", e.getMessage()));
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }
    
    // ==================== 私有辅助方法 ====================
    
    /**
     * 获取或创建 MCP 配置（确保不为 null）
     */
    private MCPServersConfig getOrCreateMcpConfig() {
        MCPServersConfig mcpConfig = config.getMcpServers();
        if (mcpConfig == null) {
            mcpConfig = new MCPServersConfig();
            config.setMcpServers(mcpConfig);
        }
        if (mcpConfig.getServers() == null) {
            mcpConfig.setServers(new ArrayList<>());
        }
        return mcpConfig;
    }
    
    /**
     * 按名称查找服务器配置
     */
    private MCPServersConfig.MCPServerConfig findServer(MCPServersConfig mcpConfig, String name) {
        return mcpConfig.getServers().stream()
                .filter(s -> s.getName().equals(name))
                .findFirst()
                .orElse(null);
    }
}