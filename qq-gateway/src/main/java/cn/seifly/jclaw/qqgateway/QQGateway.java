package cn.seifly.jclaw.qqgateway;

import cn.seifly.jclaw.qqgateway.config.*;
import cn.seifly.jclaw.qqgateway.service.JClawClient;
import cn.seifly.jclaw.qqgateway.service.Logger;
import cn.seifly.jclaw.qqgateway.service.QQWebSocketClient;
import cn.seifly.jclaw.qqgateway.service.TokenManager;

import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * QQ Gateway 主程序
 * 
 * 连接到 QQ 开放平台，接收消息并转发到 jclaw
 * 
 * 使用方法：
 * 1. 修改 config.yml 配置 App ID、App Secret 和 jclaw Webhook 地址
 * 2. 运行: mvn compile exec:java -Dexec.mainClass="cn.seifly.jclaw.qqgateway.QQGateway"
 *    或: java -jar target/qq-gateway-1.0.0.jar
 */
public class QQGateway {
    
    private static final Logger logger = Logger.getLogger("main");
    
    private static volatile boolean running = false;
    
    public static void main(String[] args) {
        logger.info("========================================");
        logger.info("     QQ Gateway for jclaw v1.0.0");
        logger.info("========================================");
        
        // 确保日志目录存在
        try {
            Files.createDirectories(Paths.get("logs"));
        } catch (Exception e) {
            // 忽略
        }
        
        // 加载配置
        GatewayConfig config = GatewayConfig.getInstance();
        
        // 验证配置
        if (!config.isValid()) {
            logger.error("配置无效！请检查 config.yml:");
            logger.error("  - appId: %s", config.getAppId() == null ? "未设置" : "已设置");
            logger.error("  - appSecret: %s", config.getAppSecret() == null ? "未设置" : "已设置");
            logger.error("  - webhookUrl: %s", config.getWebhookUrl() == null ? "未设置" : "已设置");
            System.exit(1);
        }
        
        logger.info("配置加载完成");
        logger.info("App ID: %s***", config.getAppId().substring(0, Math.min(8, config.getAppId().length())));
        logger.info("Webhook URL: %s", config.getWebhookUrl());
        
        // 初始化组件
        TokenManager tokenManager = new TokenManager(config.getAppId(), config.getAppSecret());
        JClawClient jClawClient = new JClawClient(config.getWebhookUrl());
        QQWebSocketClient wsClient = new QQWebSocketClient(
                config.getAppId(),
                config.getAppSecret(),
                tokenManager, 
                jClawClient,
                config.getReconnectDelayMs()
        );
        
        // 设置关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("收到关闭信号，正在停止...");
            running = false;
            wsClient.disconnect();
        }));
        
        // 测试 jclaw 连接
        logger.info("测试 jclaw 连接...");
        if (!jClawClient.testConnection()) {
            logger.warn("警告: 无法连接到 jclaw，请确认 jclaw 服务已启动");
        }
        
        // 连接 QQ Gateway
        running = true;
        wsClient.connect();
        
        // 主循环
        logger.info("QQ Gateway 已启动，按 Ctrl+C 停止");
        
        try {
            while (running) {
                Thread.sleep(1000);
                
                // 显示连接状态
                if (!wsClient.isConnected()) {
                    logger.debug("等待连接...");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        logger.info("QQ Gateway 已停止");
    }
}
