package cn.seifly.jclaw.channels;

import cn.seifly.jclaw.bus.OutboundMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import cn.seifly.jclaw.bus.MessageBus;
import cn.seifly.jclaw.config.ChannelsConfig;
import cn.seifly.jclaw.logger.JClawLogger;
import cn.seifly.jclaw.util.SSLUtils;
import cn.seifly.jclaw.util.StringUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class WeComChannel extends BaseChannel {

    private static final JClawLogger logger = JClawLogger.getLogger("wecom");
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    private static final String BOT_WS_URL = "wss://openws.work.weixin.qq.com";

    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final long INITIAL_RECONNECT_DELAY_MS = 1000L;
    private static final long MAX_RECONNECT_DELAY_MS = 60000L;

    private final ChannelsConfig.WeComConfig config;
    private final OkHttpClient httpClient;
    private final Map<String, String> sessionResponseUrls = new ConcurrentHashMap<>();

    private volatile boolean wsConnected = false;
    private WebSocket webSocket;
    private int reconnectAttempts = 0;

    public WeComChannel(ChannelsConfig.WeComConfig config, MessageBus bus) {
        super("wecom", bus, config.getAllowFrom());
        this.config = config;
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .sslSocketFactory(SSLUtils.getDefaultSSLSocketFactory(), SSLUtils.getDefaultTrustManager())
            .build();
    }

    @Override
    public void start() {
        logger.info("Starting WeCom channel...");

        if (config.getBotId() == null || config.getBotId().isEmpty()) {
            throw new ChannelException("WeCom Bot ID is empty");
        }
        if (config.getSecret() == null || config.getSecret().isEmpty()) {
            throw new ChannelException("WeCom Bot Secret is empty");
        }

        try {
            logger.info("Connecting to WeCom WebSocket...");
            connectWebSocketDirectly();
        } catch (Exception e) {
            logger.error("Failed to start Bot mode", Map.of("error", e.getMessage()));
            throw new ChannelException("Failed to start Bot mode: " + e.getMessage(), e);
        }

        setRunning(true);
        logger.info("WeCom channel started successfully");
    }

    private void connectWebSocketDirectly() {
        logger.info("Connecting to WeCom WebSocket (official way)", Map.of(
            "botId", config.getBotId()
        ));
        
        connectWebSocketWithUrl(BOT_WS_URL);
    }

    private void connectWebSocketWithUrl(String wsUrl) {
        logger.info("Creating WebSocket connection to", Map.of("url", wsUrl));
        
        Request request = new Request.Builder()
            .url(wsUrl)
            .addHeader("User-Agent", "aibot-node-sdk/1.0 (Java)")
            .build();

        logger.info("WebSocket request headers", Map.of("headers", request.headers().toString()));

        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                logger.debug("WeCom WebSocket connected, sending auth frame", Map.of(
                    "code", response.code(),
                    "message", response.message()
                ));
                
                try {
                    ObjectNode authFrame = objectMapper.createObjectNode();
                    authFrame.put("cmd", "aibot_subscribe");
                    ObjectNode headers = authFrame.putObject("headers");
                    headers.put("req_id", generateReqId("aibot_subscribe"));
                    ObjectNode body = authFrame.putObject("body");
                    body.put("bot_id", config.getBotId());
                    body.put("secret", config.getSecret());
                    
                    String authJson = objectMapper.writeValueAsString(authFrame);
                    logger.debug("Sending auth frame", Map.of(
                        "authFrame", authJson.replace(config.getSecret(), "***")
                    ));
                    
                    webSocket.send(authJson);
                } catch (Exception e) {
                    logger.error("Failed to send auth frame", Map.of("error", e.getMessage()));
                }
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                logger.debug("WebSocket message received", Map.of("message", text));
                
                try {
                    JsonNode json = objectMapper.readTree(text);
                    String cmd = json.path("cmd").asText("");
                    String reqId = json.path("headers").path("req_id").asText("");
                    
                    // 检查认证响应（req_id 以 "aibot_subscribe" 开头）
                    if (reqId.startsWith("aibot_subscribe")) {
                        int errcode = json.path("errcode").asInt(-1);
                        if (errcode == 0) {
                            logger.debug("WeCom WebSocket authentication successful!");
                            wsConnected = true;
                            reconnectAttempts = 0;
                        } else {
                            String errmsg = json.path("errmsg").asText("Unknown error");
                            logger.error("WeCom WebSocket authentication failed", Map.of(
                                "errcode", errcode,
                                "errmsg", errmsg
                            ));
                        }
                    } else if ("aibot_msg_callback".equals(cmd) || "aibot_event_callback".equals(cmd)) {
                        handleWsMessage(text);
                    } else {
                        logger.debug("Unhandled WebSocket frame", Map.of("cmd", cmd, "reqId", reqId));
                    }
                } catch (Exception e) {
                    logger.error("Failed to process WebSocket message", Map.of("error", e.getMessage()));
                }
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                logger.info("WeCom WebSocket closing", Map.of("code", String.valueOf(code), "reason", reason));
                wsConnected = false;
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                logger.info("WeCom WebSocket closed", Map.of("code", String.valueOf(code), "reason", reason));
                wsConnected = false;
                scheduleReconnect();
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                String responseInfo = response != null ? "code=" + response.code() + ", message=" + response.message() : "null";
                //不影响收发消息
                logger.debug("WeCom WebSocket failure", Map.of(
                    "error", t.getMessage(),
                    "response", responseInfo
                ));
                wsConnected = false;
                scheduleReconnect();
            }
        });
    }

    private void scheduleReconnect() {
        if (!isRunning()) {
            return;
        }

        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            logger.error("Max reconnect attempts reached, giving up");
            return;
        }

        long delay = Math.min(INITIAL_RECONNECT_DELAY_MS * (1L << reconnectAttempts), MAX_RECONNECT_DELAY_MS);
        reconnectAttempts++;

        logger.info("Scheduling reconnect attempt " + reconnectAttempts + " in " + delay + "ms");

        Thread reconnectThread = new Thread(() -> {
            try {
                Thread.sleep(delay);
                if (isRunning() && !wsConnected) {
                    connectWebSocketDirectly();
                }
            } catch (Exception e) {
                logger.error("Reconnect failed", Map.of("error", e.getMessage()));
            }
        }, "WeComReconnectThread");
        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }

    private void handleWsMessage(String text) {
        try {
            JsonNode json = objectMapper.readTree(text);
            String cmd = json.path("cmd").asText("");

            if ("aibot_msg_callback".equals(cmd)) {
                handleAibotMsgCallback(json);
            } else if ("aibot_event_callback".equals(cmd)) {
                handleAibotEventCallback(json);
            } else {
                logger.debug("Unhandled WS frame type", Map.of("cmd", cmd, "fullText", text));
            }
        } catch (Exception e) {
            logger.error("Error handling WS message", Map.of("error", e.getMessage()));
        }
    }

    private void handleAibotMsgCallback(JsonNode json) {
        logger.info("[DEBUG] Full message received", Map.of("json", json.toString()));

        JsonNode body = json.path("body");
        logger.info("[DEBUG] Body", Map.of("body", body.toString()));

        String chatId = body.path("chatid").asText("");
        String fromUser = body.path("from").path("userid").asText("");
        String openimId = body.path("from").path("openimId").asText("");
        String reqId = json.path("headers").path("req_id").asText("");
        String responseUrl = body.path("response_url").asText("");
        String chatType = body.path("chattype").asText("");

        logger.info("[DEBUG] Parsed fields", Map.of(
            "chatId", chatId,
            "fromUser", fromUser,
            "openimId", openimId,
            "responseUrl", responseUrl,
            "chatType", chatType,
            "reqId", reqId
        ));

        if (chatId.isEmpty() && !openimId.isEmpty()) {
            chatId = openimId;
            logger.info("[DEBUG] Using openimId as chatId", Map.of("chatId", chatId));
        }

        if (chatId.isEmpty() && !fromUser.isEmpty()) {
            chatId = fromUser;
        }

        if ("single".equals(chatType) && !responseUrl.isEmpty() && !fromUser.isEmpty()) {
            String cleanedUrl = responseUrl.trim().replaceAll("^`|`$", "");
            sessionResponseUrls.put(fromUser, cleanedUrl);
            logger.info("[DEBUG] Stored response_url for P2P chat", Map.of(
                "fromUser", fromUser,
                "responseUrlPreview", cleanedUrl.substring(0, Math.min(50, cleanedUrl.length())) + "..."
            ));
        }

        String content = null;

        JsonNode textNode = body.path("text");
        logger.info("[DEBUG] Text node", Map.of("textNode", textNode.toString()));

        if (textNode.has("content")) {
            content = textNode.path("content").asText("");
        }

        logger.info("[DEBUG] Content", Map.of("content", content));

        if (content != null && !content.isEmpty()) {
            handleBotMessage(fromUser, chatId, content, reqId);
        } else {
            logger.warn("[DEBUG] No content found in message");
        }
    }

    private void handleAibotEventCallback(JsonNode json) {
        JsonNode body = json.path("body");
        String chatId = body.path("chatid").asText("");
        String fromUser = body.path("from").path("userid").asText("");
        String openimId = body.path("from").path("openimId").asText("");
        String eventType = body.path("event").path("eventtype").asText("");

        if (chatId.isEmpty() && !openimId.isEmpty()) {
            chatId = openimId;
        }

        if ("enter_chat".equals(eventType)) {
            handleEnterChat(fromUser, chatId);
        }
    }

    private void handleBotMessage(String fromUser, String chatId, String content, String reqId) {
        logger.info("[DEBUG] handleBotMessage called", Map.of(
            "fromUser", fromUser,
            "chatId", chatId,
            "content", content,
            "reqId", reqId
        ));

        if (content.isEmpty() || fromUser.isEmpty()) {
            logger.warn("[DEBUG] Missing fromUser or content");
            return;
        }

        if (chatId.isEmpty()) {
            chatId = fromUser;
        }

        logger.info("Received WeCom Bot message", Map.of(
            "from_user", fromUser,
            "chat_id", chatId,
            "preview", StringUtils.truncate(content, 50)
        ));

        Map<String, String> metadata = new HashMap<>();
        metadata.put("platform", "wecom");
        metadata.put("mode", "bot");
        metadata.put("req_id", reqId);

        handleMessage(fromUser, chatId, content, null, metadata);
    }

    private void handleEnterChat(String fromUser, String chatId) {
        if (fromUser.isEmpty() && chatId.isEmpty()) {
            return;
        }

        if (chatId.isEmpty()) {
            chatId = fromUser;
        }

        logger.info("User entered chat", Map.of("from_user", fromUser, "chat_id", chatId));
    }

    @Override
    public void stop() {
        logger.info("Stopping WeCom channel...");
        setRunning(false);
        wsConnected = false;

        if (webSocket != null) {
            webSocket.close(1000, "Channel stopped");
            webSocket = null;
        }

        sessionResponseUrls.clear();
        logger.info("WeCom channel stopped");
    }

    @Override
    public void send(OutboundMessage message) {
        if (!isRunning()) {
            throw new IllegalStateException("WeCom channel is not running");
        }

        String chatId = message.getChatId();
        if (chatId == null || chatId.isEmpty()) {
            throw new IllegalArgumentException("Chat ID is empty");
        }

        String responseUrl = sessionResponseUrls.get(chatId);

        logger.info("Sending WeCom message", Map.of(
            "chatId", chatId,
            "wsConnected", wsConnected,
            "hasBotId", config.getBotId() != null && !config.getBotId().isEmpty(),
            "hasResponseUrl", responseUrl != null && !responseUrl.isEmpty()
        ));

        if (responseUrl != null && !responseUrl.isEmpty()) {
            sendViaResponseUrl(responseUrl, chatId, message.getContent());
        } else if (wsConnected && config.getBotId() != null && !config.getBotId().isEmpty()) {
            sendViaBotWebSocket(chatId, message.getContent());
        } else {
            throw new ChannelException("WebSocket not connected or not in Bot mode");
        }
    }

    private void sendViaResponseUrl(String responseUrl, String toUser, String content) {
        String cleanedUrl = responseUrl.trim().replaceAll("^`|`$", "");
        logger.info("[DEBUG] sendViaResponseUrl called", Map.of(
            "toUser", toUser,
            "responseUrlPreview", responseUrl.length() > 80 ? responseUrl.substring(0, 80) + "..." : responseUrl,
            "cleanedUrl", cleanedUrl.replace("response_code=", "response_code=***")
        ));

        try {
            ObjectNode responseBody = objectMapper.createObjectNode();
            responseBody.put("msgtype", "markdown");
            ObjectNode markdown = responseBody.putObject("markdown");
            markdown.put("content", content);

            String jsonBody = objectMapper.writeValueAsString(responseBody);
            logger.info("[DEBUG] Sending response via HTTP POST", Map.of("body", jsonBody));

            RequestBody body = RequestBody.create(jsonBody, JSON_MEDIA_TYPE);
            Request request = new Request.Builder()
                .url(cleanedUrl)
                .post(body)
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String responseString = response.body() != null ? response.body().string() : "";
                logger.info("[DEBUG] Response from response_url", Map.of(
                    "code", response.code(),
                    "body", responseString
                ));

                if (!response.isSuccessful()) {
                    throw new ChannelException("Failed to send message via response_url: HTTP " + response.code());
                }
            }

            logger.info("Bot message sent successfully via response_url");
        } catch (ChannelException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Failed to send Bot message via response_url", Map.of("error", e.getMessage()));
            throw new ChannelException("Failed to send Bot message via response_url: " + e.getMessage(), e);
        }
    }

    private void sendViaBotWebSocket(String toUser, String content) {
        logger.info("[DEBUG] sendViaBotWebSocket called", Map.of(
            "toUser", toUser,
            "content", content
        ));
        
        logger.info("Sending Bot message via WebSocket", Map.of(
            "toUser", toUser,
            "contentPreview", StringUtils.truncate(content, 50)
        ));

        if (webSocket == null) {
            logger.error("[DEBUG] WebSocket is null");
            throw new ChannelException("WebSocket is null, cannot send message");
        }

        if (!wsConnected) {
            logger.error("[DEBUG] WebSocket not connected");
            throw new ChannelException("WebSocket is not connected, cannot send message");
        }

        try {
            ObjectNode requestFrame = objectMapper.createObjectNode();
            requestFrame.put("cmd", "aibot_respond_msg");
            ObjectNode headers = requestFrame.putObject("headers");
            headers.put("req_id", generateReqId("aibot_respond_msg"));
            
            ObjectNode body = requestFrame.putObject("body");
            body.put("chatid", toUser);
            body.put("msgtype", "text");
            ObjectNode text = body.putObject("text");
            text.put("content", content);

            String jsonMessage = objectMapper.writeValueAsString(requestFrame);
            logger.info("[DEBUG] Sending WebSocket message", Map.of("message", jsonMessage));

            boolean sent = webSocket.send(jsonMessage);
            logger.info("[DEBUG] WebSocket send result", Map.of("success", sent));
            
            if (!sent) {
                throw new ChannelException("Failed to send WebSocket message (send returned false)");
            }

            logger.info("Bot message sent successfully via WebSocket", Map.of("toUser", toUser));
        } catch (Exception e) {
            logger.error("Failed to send Bot message via WebSocket", Map.of("error", e.getMessage()));
            e.printStackTrace();
            throw new ChannelException("Failed to send Bot message via WebSocket: " + e.getMessage(), e);
        }
    }
    
    private String generateReqId(String prefix) {
        return (prefix != null ? prefix : "req") + "_" + System.currentTimeMillis() + "_" + (int)(Math.random() * 10000);
    }

    @Override
    public boolean supportsStreaming() {
        return wsConnected && config.getBotId() != null && !config.getBotId().isEmpty();
    }

    @Override
    public cn.seifly.jclaw.providers.LLMProvider.StreamCallback createStreamingCallback(String chatId) {
        if (!supportsStreaming()) {
            return null;
        }
        return null;
    }
}
