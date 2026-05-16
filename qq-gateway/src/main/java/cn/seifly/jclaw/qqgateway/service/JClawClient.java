package cn.seifly.jclaw.qqgateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * jClaw Webhook HTTP 客户端
 * 
 * 负责将消息转发到 jclaw 的 Webhook 端点
 */
public class JClawClient {
    
    private static final Logger logger = Logger.getLogger("jclaw-client");
    
    private final okhttp3.OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String webhookUrl;
    
    public JClawClient(String webhookUrl) {
        this.webhookUrl = webhookUrl;
        this.httpClient = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * 发送消息到 jclaw Webhook
     * 
     * @param messageJson jclaw 格式的消息 JSON
     * @return true 成功，false 失败
     */
    public boolean sendMessage(String messageJson) {
        try {
            logger.debug("发送消息到 jclaw: %s", truncate(messageJson, 200));
            
            okhttp3.RequestBody body = okhttp3.RequestBody.create(
                    messageJson,
                    okhttp3.MediaType.parse("application/json; charset=utf-8")
            );
            
            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(webhookUrl)
                    .post(body)
                    .header("Content-Type", "application/json")
                    .build();
            
            try (okhttp3.Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    String responseBody = response.body() != null ? response.body().string() : "";
                    logger.debug("jclaw 响应: %s", responseBody);
                    return true;
                } else {
                    String errorBody = response.body() != null ? response.body().string() : "";
                    logger.error("jclaw 返回错误: HTTP %d - %s", response.code(), errorBody);
                    return false;
                }
            }
            
        } catch (IOException e) {
            logger.error("发送消息到 jclaw 失败: %s", e.getMessage());
            return false;
        }
    }
    
    /**
     * 测试 jclaw 连接
     */
    public boolean testConnection() {
        try {
            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(webhookUrl)
                    .get()
                    .build();
            
            try (okhttp3.Response response = httpClient.newCall(request).execute()) {
                // Webhook 是 POST 端点，GET 可能返回 405，这是正常的
                if (response.code() == 405 || response.isSuccessful()) {
                    logger.info("jclaw Webhook 连接正常");
                    return true;
                }
                logger.warn("jclaw Webhook 返回: HTTP %d", response.code());
                return false;
            }
            
        } catch (IOException e) {
            logger.error("无法连接到 jclaw: %s", e.getMessage());
            return false;
        }
    }
    
    private String truncate(String str, int maxLen) {
        if (str == null) return "null";
        if (str.length() <= maxLen) return str;
        return str.substring(0, maxLen) + "...";
    }
}
