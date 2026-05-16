package cn.seifly.jclaw.qqgateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * QQ Access Token 管理器
 * 
 * 负责获取和刷新 QQ 开放平台的 Access Token
 */
public class TokenManager {
    
    private static final Logger logger = Logger.getLogger("token");
    private static final String TOKEN_URL = "https://bots.qq.com/app/getAppAccessToken";
    
    private final String appId;
    private final String appSecret;
    private final okhttp3.OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    private volatile String accessToken;
    private volatile long tokenExpiresAt;
    
    public TokenManager(String appId, String appSecret) {
        this.appId = appId;
        this.appSecret = appSecret;
        this.httpClient = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * 获取有效的 Access Token
     * 
     * @return Access Token
     * @throws Exception 获取失败
     */
    public String getAccessToken() throws Exception {
        // 检查现有 token 是否有效（提前 5 分钟过期）
        if (accessToken != null && System.currentTimeMillis() < tokenExpiresAt - 300000) {
            return accessToken;
        }
        
        // 获取新 token
        return refreshToken();
    }
    
    /**
     * 刷新 Access Token
     */
    private String refreshToken() throws Exception {
        logger.info("正在刷新 QQ Access Token...");
        
        ObjectNode body = objectMapper.createObjectNode();
        body.put("appId", appId);
        body.put("clientSecret", appSecret);
        
        okhttp3.RequestBody requestBody = okhttp3.RequestBody.create(
                objectMapper.writeValueAsString(body),
                okhttp3.MediaType.parse("application/json")
        );
        
        okhttp3.Request request = new okhttp3.Request.Builder()
                .url(TOKEN_URL)
                .post(requestBody)
                .header("Content-Type", "application/json")
                .build();
        
        String responseBody;
        try (okhttp3.Response response = httpClient.newCall(request).execute()) {
            responseBody = response.body() != null ? response.body().string() : "";
            
            if (!response.isSuccessful()) {
                logger.error("获取 Token 失败: HTTP %d - %s", response.code(), responseBody);
                throw new IOException("获取 QQ Access Token 失败: HTTP " + response.code());
            }
        }
        
        // 解析响应
        JsonNode json = objectMapper.readTree(responseBody);
        
        String token = json.path("access_token").asText(null);
        int expiresIn = json.path("expires_in").asInt(7200);
        
        if (token == null || token.isEmpty()) {
            int code = json.path("code").asInt(-1);
            String message = json.path("message").asText("Unknown error");
            logger.error("响应中无 access_token: code=%d, message=%s, body=%s", code, message, responseBody);
            throw new IOException("获取 QQ Access Token 失败: " + message + " (code: " + code + ")");
        }
        
        this.accessToken = token;
        this.tokenExpiresAt = System.currentTimeMillis() + (expiresIn * 1000L);
        
        logger.info("QQ Access Token 获取成功，有效期: %d 秒", expiresIn);
        return token;
    }
    
    /**
     * 使 Token 失效，强制下次获取新 Token
     */
    public void invalidate() {
        this.accessToken = null;
        this.tokenExpiresAt = 0;
        logger.info("QQ Access Token 已失效");
    }
    
    /**
     * 检查 Token 是否有效
     */
    public boolean isValid() {
        return accessToken != null && System.currentTimeMillis() < tokenExpiresAt - 300000;
    }
}
