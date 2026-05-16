package cn.seifly.jclaw.qqgateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.Map;

/**
 * QQ 消息格式转换器
 * 
 * 将 QQ 开放平台的消息格式转换为 jclaw 期望的格式
 * 参考: https://github.com/RYANLEE-GEMINI/OPENCLAW-QQBOT-FORMAL
 */
public class MessageConverter {
    
    private static final Logger logger = Logger.getLogger("converter");
    private static final ObjectMapper mapper = new ObjectMapper();
    
    /**
     * QQ 开放平台消息事件类型
     */
    public static class EventTypes {
        // C2C 私聊消息 (新版)
        public static final String C2C_MESSAGE_CREATE = "C2C_MESSAGE_CREATE";
        // 频道私信消息
        public static final String DIRECT_MESSAGE_CREATE = "DIRECT_MESSAGE_CREATE";
        // 群聊 @ 消息
        public static final String GROUP_AT_MESSAGE_CREATE = "GROUP_AT_MESSAGE_CREATE";
        // 频道 @ 消息
        public static final String AT_MESSAGE_CREATE = "AT_MESSAGE_CREATE";
    }
    
    /**
     * 将 QQ 消息转换为 jclaw 格式
     * 
     * jclaw 期望的格式:
     * {
     *   "id": "消息ID",
     *   "content": "消息内容",
     *   "author": { "id": "发送者ID", "name": "发送者名称" },
     *   "group_id": "群ID"  // 可选，群聊时提供
     *   "channel_id": "频道ID"  // 可选，频道时提供
     * }
     * 
     * @param qqData QQ 开放平台的原始消息数据 (d 字段)
     * @param eventType 事件类型
     * @return 转换后的 jclaw 格式消息，如果不需要处理返回 null
     */
    public static String convertToJclawFormat(JsonNode qqData, String eventType) {
        try {
            if (qqData == null || qqData.isMissingNode()) {
                logger.debug("消息数据为空，跳过");
                return null;
            }
            
            // 只处理消息事件
            if (!isMessageEvent(eventType)) {
                return null;
            }
            
            // 构建 jclaw 格式
            ObjectNode jclawMessage = mapper.createObjectNode();
            
            // 消息 ID
            String messageId = qqData.path("id").asText(null);
            if (messageId == null || messageId.isEmpty()) {
                messageId = "msg_" + System.currentTimeMillis();
            }
            jclawMessage.put("id", messageId);
            
            // 提取消息内容
            String content = extractContent(qqData);
            if (content == null || content.isEmpty()) {
                logger.debug("消息内容为空，跳过");
                return null;
            }
            jclawMessage.put("content", content);
            
            // 发送者信息
            ObjectNode authorNode = jclawMessage.putObject("author");
            
            String authorId = null;
            String authorName = null;
            
            // 根据事件类型提取发送者信息
            switch (eventType) {
                case EventTypes.C2C_MESSAGE_CREATE:
                    authorId = qqData.path("author").path("user_openid").asText(
                            qqData.path("author").path("id").asText("unknown"));
                    authorName = qqData.path("author").path("username").asText(null);
                    break;
                    
                case EventTypes.DIRECT_MESSAGE_CREATE:
                case EventTypes.AT_MESSAGE_CREATE:
                    authorId = qqData.path("author").path("id").asText("unknown");
                    authorName = qqData.path("author").path("username").asText(null);
                    break;
                    
                case EventTypes.GROUP_AT_MESSAGE_CREATE:
                    authorId = qqData.path("author").path("member_openid").asText(
                            qqData.path("author").path("id").asText("unknown"));
                    authorName = qqData.path("author").path("nickname").asText(null);
                    break;
                    
                default:
                    authorId = qqData.path("author").path("id").asText("unknown");
            }
            
            if (authorId == null || authorId.isEmpty()) {
                authorId = "unknown";
            }
            authorNode.put("id", authorId);
            if (authorName != null && !authorName.isEmpty()) {
                authorNode.put("name", authorName);
            }
            
            // 群聊消息
            if (EventTypes.GROUP_AT_MESSAGE_CREATE.equals(eventType)) {
                String groupOpenid = qqData.path("group_openid").asText(null);
                if (groupOpenid != null && !groupOpenid.isEmpty()) {
                    jclawMessage.put("group_id", groupOpenid);
                }
                
                // 提取实际 @ 后的用户输入内容（去掉 @机器人 部分）
                content = extractAtMessageContent(content);
                jclawMessage.put("content", content);
            }
            
            // 频道消息
            if (EventTypes.AT_MESSAGE_CREATE.equals(eventType)) {
                String channelId = qqData.path("channel_id").asText(null);
                if (channelId != null && !channelId.isEmpty()) {
                    jclawMessage.put("channel_id", channelId);
                }
                String guildId = qqData.path("guild_id").asText(null);
                if (guildId != null && !guildId.isEmpty()) {
                    jclawMessage.put("guild_id", guildId);
                }
                
                // 提取实际 @ 后的用户输入内容
                content = extractAtMessageContent(content);
                jclawMessage.put("content", content);
            }
            
            // 私聊消息 (C2C / DIRECT_MESSAGE)
            if (EventTypes.C2C_MESSAGE_CREATE.equals(eventType) || 
                EventTypes.DIRECT_MESSAGE_CREATE.equals(eventType)) {
                // 私聊不需要额外处理
            }
            
            return mapper.writeValueAsString(jclawMessage);
            
        } catch (Exception e) {
            logger.error("转换消息格式失败", e);
            return null;
        }
    }
    
    /**
     * 检查是否是消息事件
     */
    private static boolean isMessageEvent(String eventType) {
        return EventTypes.C2C_MESSAGE_CREATE.equals(eventType) ||
               EventTypes.DIRECT_MESSAGE_CREATE.equals(eventType) ||
               EventTypes.GROUP_AT_MESSAGE_CREATE.equals(eventType) ||
               EventTypes.AT_MESSAGE_CREATE.equals(eventType);
    }
    
    /**
     * 提取消息内容
     * 
     * QQ 消息内容可能是：
     * 1. content 字段（直接文本）
     * 2. msg 数组（结构化消息段）
     * 3. message 数组（新格式）
     */
    private static String extractContent(JsonNode data) {
        // 直接的 content 字段
        String content = data.path("content").asText("");
        if (!content.isEmpty()) {
            return content.trim();
        }
        
        // msg 数组格式（旧格式）
        JsonNode msgArray = data.path("msg");
        if (msgArray.isArray() && msgArray.size() > 0) {
            return extractFromMessageArray(msgArray);
        }
        
        // message 数组格式（新格式）
        JsonNode messageArray = data.path("message");
        if (messageArray.isArray() && messageArray.size() > 0) {
            return extractFromMessageArray(messageArray);
        }
        
        return content;
    }
    
    /**
     * 从消息数组中提取文本内容
     */
    private static String extractFromMessageArray(JsonNode messageArray) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode segment : messageArray) {
            String type = segment.path("type").asText("");
            
            if ("text".equals(type)) {
                String text = segment.path("data").path("content").asText("");
                if (!text.isEmpty()) {
                    sb.append(text);
                }
            } else if ("image".equals(type)) {
                String url = segment.path("data").path("url").asText("");
                if (!url.isEmpty()) {
                    sb.append("[图片: ").append(url).append("]");
                }
            } else if ("audio".equals(type) || "voice".equals(type)) {
                String url = segment.path("data").path("url").asText("");
                if (!url.isEmpty()) {
                    sb.append("[语音: ").append(url).append("]");
                }
                // 检查 ASR 文本
                String asrText = segment.path("data").path("asr_text").asText("");
                if (!asrText.isEmpty()) {
                    sb.append(" (").append(asrText).append(")");
                }
            } else if ("file".equals(type)) {
                String name = segment.path("data").path("name").asText("");
                if (!name.isEmpty()) {
                    sb.append("[文件: ").append(name).append("]");
                }
            } else if ("video".equals(type)) {
                String url = segment.path("data").path("url").asText("");
                if (!url.isEmpty()) {
                    sb.append("[视频: ").append(url).append("]");
                }
            }
            // 忽略其他类型的消息段
        }
        return sb.toString().trim();
    }
    
    /**
     * 提取 @ 消息中的实际用户输入
     * 
     * QQ 群聊 @ 消息格式通常为: "@机器人 用户输入"
     */
    private static String extractAtMessageContent(String fullContent) {
        if (fullContent == null || fullContent.isEmpty()) {
            return fullContent;
        }
        
        // 检查是否有内容以 @ 开头
        if (fullContent.startsWith("@")) {
            // 找到第一个空格或换行的位置
            int atEndIndex = -1;
            for (int i = 1; i < fullContent.length(); i++) {
                char c = fullContent.charAt(i);
                if (c == ' ' || c == '\n' || c == '\t') {
                    atEndIndex = i;
                    break;
                }
            }
            if (atEndIndex > 0) {
                String content = fullContent.substring(atEndIndex).trim();
                if (!content.isEmpty()) {
                    return content;
                }
            }
        }
        return fullContent;
    }
    
    /**
     * 构建 jclaw Webhook 请求体
     */
    public static Map<String, Object> buildWebhookBody(String messageJson) {
        Map<String, Object> body = new HashMap<>();
        body.put("data", messageJson);
        body.put("timestamp", System.currentTimeMillis());
        return body;
    }
}
