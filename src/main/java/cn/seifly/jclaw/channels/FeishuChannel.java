package cn.seifly.jclaw.channels;

import com.fasterxml.jackson.databind.JsonNode;
import com.lark.oapi.Client;
import com.lark.oapi.core.utils.Jsons;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.enums.MsgTypeEnum;
import com.lark.oapi.service.im.v1.enums.ReceiveIdTypeEnum;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.CreateMessageResp;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;

import cn.seifly.jclaw.bus.InboundMessage;
import cn.seifly.jclaw.bus.MessageBus;
import cn.seifly.jclaw.bus.OutboundMessage;
import cn.seifly.jclaw.config.ChannelsConfig;
import cn.seifly.jclaw.logger.JClawLogger;
import cn.seifly.jclaw.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * 飞书通道实现 - 基于飞书开放平台
 * 
 * 提供飞书/Lark 平台的消息处理能力，支持：
 * - HTTP API 发送消息
 * - WebSocket 长连接接收消息（无需公网 IP）
 * - Webhook 接收消息（需配合外部 HTTP 服务）
 * 
 * 核心流程：
 * 1. 使用 App ID 和 App Secret 获取访问令牌
 * 2. 通过 API 发送消息
 * 3. WebSocket 模式：建立长连接接收事件推送
 * 4. Webhook 模式：接收外部 HTTP 服务推送的消息事件
 * 
 * 配置要求：
 * - App ID：飞书应用的 App ID
 * - App Secret：飞书应用的 App Secret
 * - connectionMode：连接模式，"websocket"（默认）或 "webhook"
 */
public class FeishuChannel extends BaseChannel {
    
    private static final JClawLogger logger = JClawLogger.getLogger("feishu");
    
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final long INITIAL_RECONNECT_DELAY_MS = 1000L;
    private static final long MAX_RECONNECT_DELAY_MS = 60000L;
    
    private static final String API_BASE_URL = "https://open.feishu.cn/open-apis";
    
    private final ChannelsConfig.FeishuConfig config;

    // 飞书 API 客户端（用于发送消息）
    private Client apiClient;
    
    // WebSocket 客户端（用于接收消息）
    private com.lark.oapi.ws.Client wsClient;
    
    // 重连尝试计数
    private int reconnectAttempts = 0;
    
    /**
     * 飞书事件处理器
     */
    private final EventDispatcher eventHandler;
    
    /**
     * 创建飞书通道
     * 
     * @param config 飞书配置
     * @param bus 消息总线
     */
    public FeishuChannel(ChannelsConfig.FeishuConfig config, MessageBus bus) {
        super("feishu", bus, config.getAllowFrom());
        this.config = config;
        
        // 初始化飞书 API 客户端（用于发送消息）
        this.apiClient = new Client.Builder(config.getAppId(), config.getAppSecret())
                .build();
        
        // 初始化事件处理器
        this.eventHandler = EventDispatcher.newBuilder("", "")
                .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                    @Override
                    public void handle(P2MessageReceiveV1 event) throws Exception {
                        handleMessageReceive(event);
                    }
                })
                .build();
    }
    
    @Override
    public void start() {
        logger.info("正在启动飞书通道...");
        
        if (config.getAppId() == null || config.getAppId().isEmpty() ||
            config.getAppSecret() == null || config.getAppSecret().isEmpty()) {
            throw new ChannelException("飞书 App ID 或 App Secret 为空");
        }
        
        if (config.isWebSocketMode()) {
            startWebSocketMode();
        } else {
            startWebhookMode();
        }
        
        setRunning(true);
    }
    
    /**
     * 启动 WebSocket 模式
     */
    private void startWebSocketMode() {
        logger.info("飞书通道以 WebSocket 模式启动");
        
        try {
            connectWebSocket();
            
            logger.info("飞书通道已启动（WebSocket 模式）");
        } catch (Exception e) {
            throw new ChannelException("启动飞书 WebSocket 模式失败", e);
        }
    }
    
    /**
     * 启动 Webhook 模式
     */
    private void startWebhookMode() {
        logger.info("飞书通道以 Webhook 模式启动");
        
        logger.info("飞书通道已启动（HTTP API 模式）");
        logger.info("请配合 Webhook 服务使用以接收消息");
    }
    
    @Override
    public void stop() {
        logger.info("正在停止飞书通道...");
        setRunning(false);
        
        if (wsClient != null) {
            try {
                // 飞书 SDK 没有提供 stop 方法，通过中断线程来停止
                // wsClient 内部会处理连接关闭
            } catch (Exception e) {
                logger.error("停止飞书 WebSocket 客户端失败", Map.of("error", e.getMessage()));
            }
            wsClient = null;
        }
        
        logger.info("飞书通道已停止");
    }
    
    @Override
    public void send(OutboundMessage message) {
        if (!isRunning()) {
            throw new IllegalStateException("飞书通道未运行");
        }
        
        String chatId = message.getChatId();
        if (chatId == null || chatId.isEmpty()) {
            throw new IllegalArgumentException("Chat ID 为空");
        }
        
        try {
            // 构建消息内容
            String content = String.format("{\"text\":\"%s\"}", escapeJson(message.getContent()));
            
            // 构建请求
            CreateMessageReq req = CreateMessageReq.newBuilder()
                    .receiveIdType(ReceiveIdTypeEnum.CHAT_ID.getValue())
                    .createMessageReqBody(
                            CreateMessageReqBody.newBuilder()
                                    .receiveId(chatId)
                                    .msgType(MsgTypeEnum.MSG_TYPE_TEXT.getValue())
                                    .content(content)
                                    .uuid("jclaw-" + System.nanoTime())
                                    .build()
                    )
                    .build();
            
            // 调用飞书 API 发送消息
            CreateMessageResp resp = apiClient.im().message().create(req);
            
            // 检查响应
            if (resp.getCode() != 0) {
                throw new ChannelException(String.format(
                        "发送飞书消息失败: code=%d, msg=%s",
                        resp.getCode(),
                        resp.getMsg()
                ));
            }
            
            logger.debug("飞书消息发送成功", Map.of("chat_id", chatId));
        } catch (ChannelException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelException("发送飞书消息异常: " + e.getMessage(), e);
        }
    }
    

    /**
     * 处理消息接收事件
     */
    private void handleMessageReceive(P2MessageReceiveV1 event) throws Exception {
        try {
            // 使用 Jsons 工具类将事件转换为 JSON，然后解析
            String eventJson = Jsons.DEFAULT.toJson(event);
            JsonNode json = MAPPER.readTree(eventJson);
            
            // 提取事件数据
            JsonNode eventData = json.path("event");
            if (eventData.isMissingNode()) {
                return;
            }
            
            JsonNode message = eventData.path("message");
            JsonNode sender = eventData.path("sender");
            
            String chatId = message.path("chat_id").asText(null);
            if (chatId == null || chatId.isEmpty()) {
                return;
            }
            
            // 提取发送者 ID
            String senderId = extractSenderIdFromJson(sender);
            if (senderId.isEmpty()) {
                senderId = "unknown";
            }
            
            // 提取消息内容
            String content = extractMessageContentFromJson(message);
            if (content.isEmpty()) {
                content = "[空消息]";
            }
            
            // 构建元数据
            Map<String, String> metadata = new HashMap<>();
            if (message.has("message_id")) {
                metadata.put("message_id", message.get("message_id").asText());
            }
            if (message.has("message_type")) {
                metadata.put("message_type", message.get("message_type").asText());
            }
            if (message.has("chat_type")) {
                metadata.put("chat_type", message.get("chat_type").asText());
            }
            if (sender.has("tenant_key")) {
                metadata.put("tenant_key", sender.get("tenant_key").asText());
            }
            
            logger.info("收到飞书消息", Map.of(
                "sender_id", senderId,
                "chat_id", chatId,
                "preview", StringUtils.truncate(content, 80)
            ));
            
            // 通过父类统一处理权限校验和消息发布
            InboundMessage msg = handleMessage(senderId, chatId, content, null, metadata);
            
            if (msg != null) {
                logger.debug("飞书消息已发布到总线", Map.of(
                    "channel", msg.getChannel(),
                    "session_key", msg.getSessionKey()
                ));
            } else {
                logger.warn("飞书消息被拒绝（可能权限不足）", Map.of(
                    "sender_id", senderId
                ));
            }
        } catch (Exception e) {
            logger.error("处理飞书消息事件时出错", Map.of("error", e.getMessage()));
            throw e;
        }
    }
    
    /**
     * 连接 WebSocket（使用飞书官方 SDK）
     */
    private void connectWebSocket() {
        // 创建飞书 WebSocket 客户端
        wsClient = new com.lark.oapi.ws.Client.Builder(config.getAppId(), config.getAppSecret())
                .eventHandler(eventHandler)
                .build();
        
        // 启动 WebSocket 连接（阻塞调用，需要在后台线程中运行）
        Thread wsThread = new Thread(() -> {
            try {
                wsClient.start();
            } catch (Exception e) {
                logger.error("飞书 WebSocket 客户端异常", Map.of("error", e.getMessage()));
            }
        });
        wsThread.setDaemon(true);
        wsThread.setName("FeishuWebSocketClient");
        wsThread.start();
        
        logger.info("飞书 WebSocket 客户端已启动");
    }
    
    /**
     * 处理接收到的飞书消息事件（由外部 Webhook 调用）
     * 
     * @param messageJson 消息 JSON 字符串
     */
    public void handleIncomingMessage(String messageJson) {
        try {
            JsonNode json = MAPPER.readTree(messageJson);
            
            // 提取消息信息
            JsonNode event = json.path("event");
            JsonNode message = event.path("message");
            JsonNode sender = event.path("sender");
            
            String chatId = message.path("chat_id").asText(null);
            if (chatId == null || chatId.isEmpty()) {
                return;
            }
            
            // 提取发送者 ID
            String senderId = extractSenderIdFromJson(sender);
            if (senderId.isEmpty()) {
                senderId = "unknown";
            }
            
            // 提取消息内容
            String content = extractMessageContentFromJson(message);
            if (content.isEmpty()) {
                content = "[空消息]";
            }
            
            // 构建元数据
            Map<String, String> metadata = new HashMap<>();
            if (message.has("message_id")) {
                metadata.put("message_id", message.get("message_id").asText());
            }
            if (message.has("message_type")) {
                metadata.put("message_type", message.get("message_type").asText());
            }
            if (message.has("chat_type")) {
                metadata.put("chat_type", message.get("chat_type").asText());
            }
            if (sender.has("tenant_key")) {
                metadata.put("tenant_key", sender.get("tenant_key").asText());
            }
            
            logger.info("收到飞书消息", Map.of(
                "sender_id", senderId,
                "chat_id", chatId,
                "preview", StringUtils.truncate(content, 80)
            ));
            
            // 通过父类统一处理权限校验和消息发布
            handleMessage(senderId, chatId, content, null, metadata);
            
        } catch (Exception e) {
            logger.error("处理飞书消息时出错", Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * 从 JSON 中提取发送者 ID
     */
    private String extractSenderIdFromJson(JsonNode sender) {
        if (sender == null || !sender.has("sender_id")) {
            return "";
        }
        
        JsonNode senderId = sender.get("sender_id");
        
        if (senderId.has("user_id") && !senderId.get("user_id").asText().isEmpty()) {
            return senderId.get("user_id").asText();
        }
        if (senderId.has("open_id") && !senderId.get("open_id").asText().isEmpty()) {
            return senderId.get("open_id").asText();
        }
        if (senderId.has("union_id") && !senderId.get("union_id").asText().isEmpty()) {
            return senderId.get("union_id").asText();
        }
        
        return "";
    }
    
    /**
     * 从 JSON 中提取消息内容
     */
    private String extractMessageContentFromJson(JsonNode message) {
        if (message == null || !message.has("content")) {
            return "";
        }
        
        String contentStr = message.get("content").asText("");
        
        // 处理文本消息
        if ("text".equals(message.path("message_type").asText(""))) {
            try {
                JsonNode contentNode = MAPPER.readTree(contentStr);
                if (contentNode.has("text")) {
                    return contentNode.get("text").asText();
                }
            } catch (Exception e) {
                // 解析失败，返回原始内容
            }
        }
        
        return contentStr;
    }
    
    /**
     * JSON 字符串转义
     */
    private String escapeJson(String text) {
        if (text == null) return "";
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }
}
