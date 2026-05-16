package cn.seifly.jclaw.qqgateway.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;

/**
 * QQ Gateway 配置管理
 * 从 ~/.jclaw/config.json 读取 QQ 通道配置
 */
public class GatewayConfig {
    
    private String appId;
    private String appSecret;
    private String webhookUrl = "http://localhost:18790/api/channels/qq/webhook";
    private long reconnectDelayMs = 5000;
    private long heartbeatIntervalMs = 30000;
    private String logLevel = "INFO";

    private static final String JCLAW_CONFIG_PATH = System.getProperty("user.home") + "/.jclaw/config.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static GatewayConfig instance;

    public static GatewayConfig getInstance() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static GatewayConfig load() {
        GatewayConfig config = new GatewayConfig();
        
        System.out.println("正在加载 jclaw 配置: " + JCLAW_CONFIG_PATH);
        
        File configFile = new File(JCLAW_CONFIG_PATH);
        if (!configFile.exists()) {
            System.err.println("错误: 配置文件不存在: " + JCLAW_CONFIG_PATH);
            System.err.println("请先启动 jclaw 以生成配置文件");
            return config;
        }
        
        try {
            JsonNode root = MAPPER.readTree(configFile);
            JsonNode qqConfig = root.path("channels").path("qq");
            
            if (qqConfig.isMissingNode()) {
                System.err.println("警告: 配置文件中未找到 channels.qq 配置");
                return config;
            }
            
            // 读取 QQ 配置
            config.appId = qqConfig.path("appId").asText(null);
            config.appSecret = qqConfig.path("appSecret").asText(null);
            
            // 验证必填字段
            if (config.appId == null || config.appId.isEmpty()) {
                System.err.println("错误: 配置中缺少 appId");
            }
            
            if (config.appSecret == null || config.appSecret.isEmpty()) {
                System.err.println("错误: 配置中缺少 appSecret");
            }
            
            System.out.println("✓ QQ 配置加载成功");
            System.out.println("  App ID: " + maskString(config.appId));
            System.out.println("  Webhook URL: " + config.webhookUrl);
            
        } catch (IOException e) {
            System.err.println("错误: 无法加载配置文件: " + e.getMessage());
            e.printStackTrace();
        }
        
        return config;
    }
    
    /**
     * 掩码字符串用于日志输出
     */
    private static String maskString(String str) {
        if (str == null || str.length() <= 8) {
            return "***";
        }
        return str.substring(0, 4) + "..." + str.substring(str.length() - 4);
    }

    // Getters
    public String getAppId() { return appId; }
    public String getAppSecret() { return appSecret; }
    public String getWebhookUrl() { return webhookUrl; }
    public long getReconnectDelayMs() { return reconnectDelayMs; }
    public long getHeartbeatIntervalMs() { return heartbeatIntervalMs; }
    public String getLogLevel() { return logLevel; }
    
    // 验证配置
    public boolean isValid() {
        return appId != null && !appId.isEmpty() 
            && appSecret != null && !appSecret.isEmpty()
            && webhookUrl != null && !webhookUrl.isEmpty();
    }
}
