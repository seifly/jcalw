package cn.seifly.jclaw.qqgateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * QQ 开放平台 WebSocket 客户端
 * 
 * 参考 openclaw QQ 插件的实现：
 * https://github.com/RYANLEE-GEMINI/OPENCLAW-QQBOT-FORMAL
 */
public class QQWebSocketClient {
    
    private static final Logger logger = Logger.getLogger("qq-ws");
    
    // QQ API Base
    private static final String API_BASE = "https://api.sgroup.qq.com";
    
    // QQ Gateway Op Codes
    private static final int OP_DISPATCH = 0;
    private static final int OP_HEARTBEAT = 1;
    private static final int OP_IDENTIFY = 2;
    private static final int OP_RESUME = 6;
    private static final int OP_RECONNECT = 7;
    private static final int OP_INVALID_SESSION = 9;
    private static final int OP_HELLO = 10;
    private static final int OP_HEARTBEAT_ACK = 11;
    
    // QQ Intents (参考 openclaw)
    // PUBLIC_GUILD_MESSAGES = 1 << 30
    // DIRECT_MESSAGE = 1 << 12
    // GROUP_AND_C2C = 1 << 25
    private static final int INTENT_PUBLIC_GUILD_MESSAGES = 1 << 30;
    private static final int INTENT_DIRECT_MESSAGE = 1 << 12;
    private static final int INTENT_GROUP_AND_C2C = 1 << 25;
    private static final int FULL_INTENTS = INTENT_PUBLIC_GUILD_MESSAGES | INTENT_DIRECT_MESSAGE | INTENT_GROUP_AND_C2C;
    
    private final String appId;
    private final String appSecret;
    private final TokenManager tokenManager;
    private final JClawClient jClawClient;
    private final ObjectMapper objectMapper;
    
    private final long reconnectDelayMs;
    
    private WebSocket webSocket;
    private OkHttpClient httpClient;
    private java.util.concurrent.ScheduledExecutorService scheduler;
    private java.util.concurrent.ScheduledFuture<?> heartbeatTask;
    
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean shouldReconnect = new AtomicBoolean(true);
    private final AtomicInteger sequence = new AtomicInteger(0);
    
    private String sessionId;
    private int lastSeq = 0;
    private String accessToken;
    
    public QQWebSocketClient(String appId, String appSecret, TokenManager tokenManager, 
                            JClawClient jClawClient, long reconnectDelayMs) {
        this.appId = appId;
        this.appSecret = appSecret;
        this.tokenManager = tokenManager;
        this.jClawClient = jClawClient;
        this.reconnectDelayMs = reconnectDelayMs;
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * 连接到 QQ Gateway
     */
    public void connect() {
        shouldReconnect.set(true);
        doConnect();
    }
    
    /**
     * 断开连接
     */
    public void disconnect() {
        shouldReconnect.set(false);
        cleanup();
    }
    
    private void doConnect() {
        try {
            // 先获取 access token
            accessToken = tokenManager.getAccessToken();
            logger.info("Access token 获取成功");
            
            // 初始化 httpClient (必须在 getGatewayUrl 之前)
            httpClient = new OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.SECONDS)  // WebSocket 不设读超时
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .build();
            
            // 通过 API 获取 Gateway URL
            String gatewayUrl = getGatewayUrl(accessToken);
            logger.info("Gateway URL: %s", gatewayUrl);
            
            Request request = new Request.Builder()
                    .url(gatewayUrl)
                    .header("Accept", "application/json")
                    .build();
            
            webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
                
                @Override
                public void onOpen(WebSocket ws, Response response) {
                    logger.info("QQ Gateway WebSocket 连接成功");
                    connected.set(true);
                }
                
                @Override
                public void onMessage(WebSocket ws, String text) {
                    handleMessage(text);
                }
                
                @Override
                public void onClosing(WebSocket ws, int code, String reason) {
                    logger.warn("QQ Gateway 正在关闭: code=%d, reason=%s", code, reason);
                    ws.close(1000, "Normal closure");
                }
                
                @Override
                public void onClosed(WebSocket ws, int code, String reason) {
                    logger.info("QQ Gateway 连接已关闭: code=%d, reason=%s", code, reason);
                    connected.set(false);
                    stopHeartbeat();
                    
                    // 根据错误码处理
                    if (code == 4914 || code == 4915) {
                        logger.error("机器人已%s，请联系 QQ 平台", code == 4914 ? "下架" : "封禁");
                        shouldReconnect.set(false);
                        return;
                    }
                    
                    if (code == 4004) {
                        logger.warn("Token 无效 (4004)，将刷新 token 并重连");
                        tokenManager.invalidate();
                    }
                    
                    if (shouldReconnect.get()) {
                        scheduleReconnect();
                    }
                }
                
                @Override
                public void onFailure(WebSocket ws, Throwable t, Response response) {
                    logger.error("QQ Gateway 连接失败: %s", t.getMessage());
                    connected.set(false);
                    stopHeartbeat();
                    
                    if (shouldReconnect.get()) {
                        scheduleReconnect();
                    }
                }
            });
            
        } catch (Exception e) {
            logger.error("连接 QQ Gateway 失败: %s", e.getMessage());
            if (shouldReconnect.get()) {
                scheduleReconnect();
            }
        }
    }
    
    /**
     * 通过 API 获取 Gateway URL
     */
    private String getGatewayUrl(String accessToken) throws IOException {
        Request request = new Request.Builder()
                .url(API_BASE + "/gateway")
                .header("Authorization", "QQBot " + accessToken)
                .get()
                .build();
        
        try (okhttp3.Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("获取 Gateway URL 失败: HTTP " + response.code() + " - " + body);
            }
            
            JsonNode json = objectMapper.readTree(body);
            String url = json.path("url").asText(null);
            if (url == null || url.isEmpty()) {
                throw new IOException("响应中无 url 字段: " + body);
            }
            return url;
        }
    }
    
    /**
     * 处理收到的 WebSocket 消息
     */
    private void handleMessage(String text) {
        try {
            JsonNode message = objectMapper.readTree(text);
            int opCode = message.path("op").asInt();
            JsonNode data = message.path("d");
            
            // 保存 sequence 号
            if (message.has("s") && !message.get("s").isNull()) {
                lastSeq = message.get("s").asInt();
                sequence.set(lastSeq);
            }
            
            // 获取事件类型
            String eventType = message.path("t").asText("");
            
            logger.debug("收到消息: op=%d, t=%s", opCode, eventType);
            
            switch (opCode) {
                case OP_HELLO:
                    handleHello(data);
                    break;
                    
                case OP_HEARTBEAT_ACK:
                    logger.debug("心跳 ACK 收到");
                    break;
                    
                case OP_DISPATCH:
                    handleDispatch(data, eventType);
                    break;
                    
                case OP_INVALID_SESSION:
                    boolean canResume = data.asBoolean(false);
                    logger.warn("收到 INVALID_SESSION, canResume=%s", canResume);
                    if (!canResume) {
                        sessionId = null;
                    }
                    break;
                    
                case OP_RECONNECT:
                    logger.warn("收到 RECONNECT 请求");
                    cleanup();
                    connect();
                    break;
                    
                default:
                    logger.debug("收到未处理的消息类型: op=%d", opCode);
            }
            
        } catch (Exception e) {
            logger.error("处理消息失败: %s, text=%s", e.getMessage(), truncate(text, 500));
        }
    }
    
    /**
     * 处理 HELLO 事件
     */
    private void handleHello(JsonNode data) {
        logger.info("收到 QQ Gateway Hello 消息");
        
        // 启动心跳
        long heartbeatInterval = data.path("heartbeat_interval").asLong(30000);
        logger.info("心跳间隔: %d ms", heartbeatInterval);
        startHeartbeat(heartbeatInterval);
        
        // 发送 IDENTIFY
        if (sessionId != null && lastSeq > 0) {
            logger.info("尝试恢复连接 sessionId=%s, seq=%d", sessionId, lastSeq);
            sendResume();
        } else {
            logger.info("发送 IDENTIFY");
            sendIdentify();
        }
    }
    
    /**
     * 处理事件分发
     */
    private void handleDispatch(JsonNode data, String eventType) {
        logger.info("收到事件: %s", eventType);
        
        // 处理不同类型的事件
        switch (eventType) {
            case "READY":
                handleReady(data);
                break;
                
            case "RESUMED":
                logger.info("会话已恢复");
                break;
                
            case "C2C_MESSAGE_CREATE":
            case "DIRECT_MESSAGE_CREATE":
            case "GROUP_AT_MESSAGE_CREATE":
            case "AT_MESSAGE_CREATE":
                handleMessageEvent(data, eventType);
                break;
                
            default:
                logger.debug("未处理的事件类型: %s", eventType);
        }
    }
    
    /**
     * 处理 READY 事件
     */
    private void handleReady(JsonNode data) {
        sessionId = data.path("session_id").asText(null);
        logger.info("连接就绪, sessionId=%s, intents=0x%x", sessionId, FULL_INTENTS);
    }
    
    /**
     * 处理消息事件
     */
    private void handleMessageEvent(JsonNode data, String eventType) {
        try {
            String messageJson = MessageConverter.convertToJclawFormat(data, eventType);
            
            if (messageJson != null) {
                JsonNode temp = objectMapper.readTree(messageJson);
                String senderId = temp.path("author").path("id").asText("unknown");
                String content = temp.path("content").asText("");
                
                logger.info("收到 QQ 消息 - type: %s, sender: %s, content: %s",
                        eventType, senderId, truncate(content, 50));
                
                boolean success = jClawClient.sendMessage(messageJson);
                if (success) {
                    logger.debug("消息已转发到 jclaw");
                } else {
                    logger.warn("消息转发到 jclaw 失败");
                }
            }
        } catch (Exception e) {
            logger.error("处理消息事件失败: %s", e.getMessage());
        }
    }
    
    /**
     * 发送 IDENTIFY 建立连接
     * 
     * 参考 openclaw: token 格式为 "QQBot " + accessToken
     */
    private void sendIdentify() {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("op", OP_IDENTIFY);
            
            ObjectNode d = payload.putObject("d");
            // 关键: token 格式是 "QQBot " + accessToken
            d.put("token", "QQBot " + accessToken);
            d.put("intents", FULL_INTENTS);
            
            // shard 参数 (分片)
            ArrayNode shard = d.putArray("shard");
            shard.add(0);
            shard.add(1);
            
            String json = objectMapper.writeValueAsString(payload);
            logger.debug("发送 IDENTIFY: %s", json);
            
            webSocket.send(json);
            logger.info("IDENTIFY 已发送, intents=0x%x", FULL_INTENTS);
            
        } catch (Exception e) {
            logger.error("发送 IDENTIFY 失败", e);
        }
    }
    
    /**
     * 发送 RESUME 恢复连接
     */
    private void sendResume() {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("op", OP_RESUME);
            
            ObjectNode d = payload.putObject("d");
            d.put("token", "QQBot " + accessToken);
            d.put("session_id", sessionId);
            d.put("seq", lastSeq);
            
            String json = objectMapper.writeValueAsString(payload);
            logger.debug("发送 RESUME: %s", json);
            
            webSocket.send(json);
            logger.info("RESUME 已发送");
            
        } catch (Exception e) {
            logger.error("发送 RESUME 失败", e);
        }
    }
    
    /**
     * 发送心跳
     */
    private void sendHeartbeat() {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("op", OP_HEARTBEAT);
            payload.put("d", lastSeq);
            
            String json = objectMapper.writeValueAsString(payload);
            webSocket.send(json);
            logger.debug("心跳已发送, seq=%d", lastSeq);
            
        } catch (Exception e) {
            logger.error("发送心跳失败", e);
        }
    }
    
    /**
     * 启动心跳定时器
     */
    private void startHeartbeat(long intervalMs) {
        stopHeartbeat();
        
        if (scheduler == null) {
            scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                    r -> new Thread(r, "qq-heartbeat")
            );
        }
        
        heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
            if (connected.get() && webSocket != null) {
                sendHeartbeat();
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        
        logger.info("心跳已启动，间隔: %d ms", intervalMs);
    }
    
    /**
     * 停止心跳
     */
    private void stopHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
        }
    }
    
    /**
     * 安排重连
     */
    private void scheduleReconnect() {
        logger.info("%d 毫秒后尝试重连...", reconnectDelayMs);
        
        new Thread(() -> {
            try {
                Thread.sleep(reconnectDelayMs);
                logger.info("开始重连...");
                doConnect();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                logger.error("重连失败", e);
            }
        }, "qq-reconnect").start();
    }
    
    /**
     * 清理资源
     */
    private void cleanup() {
        stopHeartbeat();
        
        if (scheduler != null) {
            scheduler.shutdown();
            scheduler = null;
        }
        
        if (webSocket != null) {
            webSocket.close(1000, "User disconnect");
            webSocket = null;
        }
        
        connected.set(false);
    }
    
    /**
     * 检查是否已连接
     */
    public boolean isConnected() {
        return connected.get();
    }
    
    private String truncate(String str, int maxLen) {
        if (str == null) return "null";
        if (str.length() <= maxLen) return str;
        return str.substring(0, maxLen) + "...";
    }
}
