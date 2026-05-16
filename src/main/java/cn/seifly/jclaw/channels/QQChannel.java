package cn.seifly.jclaw.channels;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cn.seifly.jclaw.bus.MessageBus;
import cn.seifly.jclaw.bus.OutboundMessage;
import cn.seifly.jclaw.config.ChannelsConfig;
import cn.seifly.jclaw.logger.JClawLogger;
import okhttp3.OkHttpClient;
import okhttp3.Request;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * QQ 通道实现 - 基于腾讯 QQ 开放平台 API
 * 
 * 提供腾讯 QQ 机器人的消息处理能力：
 * - 私聊消息收发
 * - 群聊 @ 消息处理
 * - WebSocket 消息接收（需配合网关服务）
 * 
 * 核心流程：
 * 1. 使用 App ID 和 App Secret 获取访问令牌
 * 2. 通过 WebSocket 或 HTTP 接收消息事件
 * 3. 解析消息内容并发布到消息总线
 * 4. 使用 API 发送回复消息
 * 
 * 配置要求：
 * - App ID：QQ 机器人的 App ID
 * - App Secret：QQ 机器人的 App Secret
 * 
 * 注意：
 * - 需要在 QQ 开放平台注册机器人应用
 * - 消息接收需要配合网关服务使用
 */
public class QQChannel extends BaseChannel {
    
    private static final JClawLogger logger = JClawLogger.getLogger("qq");
    
    private static final String API_BASE_URL = "https://api.sgroup.qq.com";
    
    private final ChannelsConfig.QQConfig config;
    private final OkHttpClient httpClient;
    
    // 令牌管理器
    private final TokenManager tokenManager;
    
    // 已处理消息 ID（去重）
    private final Set<String> processedIds = ConcurrentHashMap.newKeySet();
    
    /**
     * 创建 QQ 通道
     * 
     * @param config QQ 配置
     * @param bus 消息总线
     */
    public QQChannel(ChannelsConfig.QQConfig config, MessageBus bus) {
        super("qq", bus, config.getAllowFrom());
        this.config = config;
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();
        
        // 初始化令牌管理器
        this.tokenManager = new TokenManager(this::fetchAccessToken);
    }
    
    @Override
    public void start() throws ChannelException {
        logger.info("正在启动 QQ 通道...");
        
        // 打印配置信息用于调试
        logger.info("QQ 通道配置检查", Map.of(
            "enabled", true,
            "app_id_present", config.getAppId() != null && !config.getAppId().isEmpty(),
            "app_secret_present", config.getAppSecret() != null && !config.getAppSecret().isEmpty(),
            "allow_from_size", config.getAllowFrom() != null ? config.getAllowFrom().size() : 0
        ));
        
        if (config.getAppId() == null || config.getAppId().isEmpty()) {
            logger.error("QQ App ID 为空，无法启动通道");
            throw new ChannelException("QQ App ID 为空");
        }
        
        if (config.getAppSecret() == null || config.getAppSecret().isEmpty()) {
            logger.error("QQ App Secret 为空，无法启动通道");
            throw new ChannelException("QQ App Secret 为空");
        }
        
        // 获取访问令牌
        try {
            logger.info("正在获取 QQ 访问令牌...");
            String token = tokenManager.getValidToken();
            logger.info("QQ 访问令牌获取成功", Map.of("token_length", token.length()));
        } catch (Exception e) {
            logger.error("获取 QQ 访问令牌失败", Map.of("error", e.getMessage(), "error_type", e.getClass().getSimpleName()));
            throw new ChannelException("获取访问令牌失败: " + e.getMessage(), e);
        }
        
        setRunning(true);
        logger.info("QQ 通道已启动（API 模式）");
        logger.info("请配合网关服务使用以接收消息");
        logger.info("QQ 通道状态", Map.of(
            "running", isRunning(),
            "channel_name", name()
        ));
    }
    
    @Override
    public void stop() {
        logger.info("正在停止 QQ 通道...");
        setRunning(false);
        tokenManager.invalidate();
        processedIds.clear();
        logger.info("QQ 通道已停止");
    }
    
    @Override
    public void send(OutboundMessage message) {
        if (!isRunning()) {
            logger.error("QQ 通道未运行，无法发送消息", Map.of(
                "chat_id", message.getChatId(),
                "content_preview", message.getContent() != null ? message.getContent().substring(0, Math.min(50, message.getContent().length())) : "null"
            ));
            throw new IllegalStateException("QQ 通道未运行");
        }
        
        // 获取有效令牌
        String token;
        try {
            token = tokenManager.getValidToken();
            logger.debug("QQ 令牌获取成功");
        } catch (Exception e) {
            logger.error("刷新 QQ 访问令牌失败", Map.of("error", e.getMessage()));
            throw new ChannelException("刷新访问令牌失败: " + e.getMessage(), e);
        }
        
        // 清理消息内容，移除不支持的字符
        // QQ 开放平台可能不支持某些 emoji 和特殊字符
        String cleanContent = cleanContentForQQ(message.getContent());
        
        logger.info("准备发送 QQ 消息", Map.of(
            "chat_id", message.getChatId(),
            "content_length", cleanContent.length(),
            "content_preview", cleanContent.substring(0, Math.min(50, cleanContent.length()))
        ));
        
        // 构建消息体 - 参考 openclaw 的格式
        // QQ API v2 使用简单的 content 字段，不是 msg 数组
        ObjectNode body = MAPPER.createObjectNode();
        body.put("content", cleanContent);
        body.put("msg_type", 0);  // 0 = 文本消息
        
        String jsonBody;
        try {
            jsonBody = MAPPER.writeValueAsString(body);
            logger.info("QQ 消息体构建成功", Map.of(
                "body_length", jsonBody.length(),
                "body_preview", jsonBody
            ));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            logger.error("QQ 消息序列化失败", Map.of("error", e.getMessage()));
            throw new ChannelException("序列化消息失败: " + e.getMessage(), e);
        }
        
        // 发送消息 - 根据 chat_id 格式选择正确的 API 端点
        // 参考 openclaw：使用 /v2/users/{openid}/messages
        String chatId = message.getChatId();
        String url;
        
        // 检查是否是群聊消息（有 group_id 在 metadata 中）
        String groupId = message.getMetadata() != null ? message.getMetadata().get("group_id") : null;
        
        if (groupId != null && !groupId.isEmpty()) {
            // 群聊消息 - 使用 groups 端点
            url = API_BASE_URL + "/v2/groups/" + groupId + "/messages";
            logger.info("检测到群聊消息，使用 groups 端点", Map.of("group_id", groupId));
        } else {
            // 私聊消息 - 使用 users 端点（openid 格式）
            url = API_BASE_URL + "/v2/users/" + chatId + "/messages";
            logger.info("发送私聊消息，使用 users 端点", Map.of("chat_id", chatId));
        }
        
        Request request = buildJsonPostRequest(url, jsonBody, "QQBot " + token);
        
        logger.info("正在发送 QQ 消息到 API", Map.of(
            "url", url,
            "chat_id", message.getChatId()
        ));
        
        try {
            executeRequest(httpClient, request);
            logger.info("QQ 消息发送成功", Map.of("chat_id", message.getChatId()));
        } catch (java.io.IOException e) {
            logger.error("发送 QQ 消息失败", Map.of(
                "chat_id", message.getChatId(),
                "error", e.getMessage(),
                "error_type", e.getClass().getSimpleName()
            ));
            throw new ChannelException("发送 QQ 消息失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 清理消息内容，移除 QQ 开放平台不支持的字符
     * 
     * QQ 机器人 API 可能不支持某些特殊字符和 emoji
     */
    private String cleanContentForQQ(String content) {
        if (content == null) {
            return "";
        }
        
        // 过滤掉非基本字符（包括 emoji）
        // 保留：中文、英文、数字、基础标点、空格、换行
        StringBuilder cleaned = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            int codePoint = content.codePointAt(i);
            
            // 跳过补充平面字符（emoji 多数在此范围）
            if (codePoint > 0xFFFF) {
                continue;
            }
            
            char c = (char) codePoint;
            
            // 检查 Unicode 区块
            Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
            
            // 保留的字符类型
            if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
                block == Character.UnicodeBlock.BASIC_LATIN ||
                block == Character.UnicodeBlock.GENERAL_PUNCTUATION ||
                block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS ||
                Character.isLetterOrDigit(c) ||
                Character.isWhitespace(c) ||
                c == '\n' || c == '\r' || c == '\t') {
                cleaned.append(c);
            }
            // 其他字符（如 emoji）将被跳过
        }
        
        return cleaned.toString().trim();
    }
    
    /**
     * 获取访问令牌（供 TokenManager 调用）
     */
    private TokenManager.TokenResult fetchAccessToken() throws Exception {
        String url = "https://bots.qq.com/app/getAppAccessToken";
        
        logger.info("正在请求 QQ 访问令牌", Map.of("url", url, "app_id", config.getAppId()));
        
        ObjectNode body = MAPPER.createObjectNode();
        body.put("appId", config.getAppId());
        body.put("clientSecret", config.getAppSecret());
        
        String jsonBody = MAPPER.writeValueAsString(body);
        Request request = buildJsonPostRequest(url, jsonBody);
        
        String responseBody;
        try {
            responseBody = executeRequest(httpClient, request);
            logger.debug("QQ 令牌请求响应成功", Map.of("response_length", responseBody.length()));
        } catch (Exception e) {
            logger.error("QQ 令牌请求失败", Map.of("error", e.getMessage(), "url", url));
            throw e;
        }
        
        JsonNode json = MAPPER.readTree(responseBody);
        
        String token = json.path("access_token").asText(null);
        int expiresIn = json.path("expires_in").asInt(7200);
        
        if (token == null || token.isEmpty()) {
            logger.error("QQ 响应中无 access_token", Map.of("response", responseBody));
            throw new Exception("获取 QQ 访问令牌失败: 响应中无 access_token. Response: " + responseBody);
        }
        
        logger.info("QQ 访问令牌已刷新", Map.of(
            "expires_in", expiresIn,
            "token_length", token.length()
        ));
        return new TokenManager.TokenResult(token, expiresIn);
    }
    
    /**
     * 处理接收到的消息（由外部网关调用）
     * 
     * @param messageJson 消息 JSON 字符串
     */
    public void handleIncomingMessage(String messageJson) {
        logger.debug("收到 QQ 原始消息", Map.of("json_length", messageJson != null ? messageJson.length() : 0));
        
        try {
            JsonNode json = MAPPER.readTree(messageJson);
            
            String messageId = json.path("id").asText(null);
            if (messageId == null) {
                logger.warn("QQ 消息缺少 ID，跳过处理");
                return;
            }
            
            // 去重检查
            if (processedIds.contains(messageId)) {
                logger.debug("QQ 消息已处理过，跳过", Map.of("message_id", messageId));
                return;
            }
            processedIds.add(messageId);
            
            // 清理过期的消息 ID
            if (processedIds.size() > 10000) {
                logger.info("清理 QQ 消息 ID 缓存", Map.of("old_size", processedIds.size()));
                processedIds.clear();
            }
            
            // 提取发送者信息
            JsonNode author = json.path("author");
            String senderId = author.path("id").asText("unknown");
            if (senderId.equals("unknown")) {
                logger.warn("QQ 消息缺少发送者 ID，跳过处理", Map.of("message_id", messageId));
                return;
            }
            
            // 提取消息内容
            String content = json.path("content").asText("");
            if (content.isEmpty()) {
                logger.debug("QQ 消息内容为空，跳过处理", Map.of("message_id", messageId));
                return;
            }
            
            // 确定 chat ID
            String chatId = senderId;
            String groupId = json.path("group_id").asText(null);
            if (groupId != null && !groupId.isEmpty()) {
                chatId = groupId;
                logger.debug("QQ 群聊消息", Map.of("group_id", groupId, "sender_id", senderId));
            }
            
            logger.info("收到 QQ 消息", Map.of(
                "sender_id", senderId,
                "chat_id", chatId,
                "message_id", messageId,
                "content_length", content.length(),
                "content_preview", content.substring(0, Math.min(50, content.length()))
            ));
            
            // 构建元数据
            Map<String, String> metadata = new HashMap<>();
            metadata.put("message_id", messageId);
            if (groupId != null) {
                metadata.put("group_id", groupId);
            }
            
            // 通过父类统一处理权限校验和消息发布
            handleMessage(senderId, chatId, content, null, metadata);
            logger.debug("QQ 消息已发布到消息总线");
            
        } catch (Exception e) {
            logger.error("处理 QQ 消息时出错", Map.of(
                "error", e.getMessage(),
                "error_type", e.getClass().getSimpleName(),
                "json_preview", messageJson != null ? messageJson.substring(0, Math.min(200, messageJson.length())) : "null"
            ));
        }
    }
}
