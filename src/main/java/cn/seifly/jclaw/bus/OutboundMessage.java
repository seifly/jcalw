package cn.seifly.jclaw.bus;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 发送到外部通道的出站消息
 */
public class OutboundMessage {

    /**
     * 消息类型枚举，用于指导各通道选择合适的发送格式
     */
    public enum MessageType {
        /** 纯文本消息 */
        TEXT,
        /** Markdown 格式消息（部分通道支持） */
        MARKDOWN,
        /** 富文本卡片消息（钉钉/飞书等支持） */
        CARD
    }

    private String channel;
    private String chatId;
    private String content;

    /**
     * 关联的会话键，便于链路追踪，与触发此出站消息的入站消息保持一致
     */
    private String sessionKey;

    /**
     * 消息类型，默认为纯文本
     */
    private MessageType messageType = MessageType.TEXT;

    /**
     * 消息创建时间戳，用于链路追踪和超时判断
     */
    private final Instant createdAt = Instant.now();

    /**
     * 元数据，用于传递通道特定的信息（如群组 ID 等）
     */
    private Map<String, String> metadata;

    public OutboundMessage() {
    }

    /**
     * 标准构造器，兼容现有所有调用方
     */
    public OutboundMessage(String channel, String chatId, String content) {
        this.channel = channel;
        this.chatId = chatId;
        this.content = content;
    }

    /**
     * 带会话键的构造器，用于需要链路追踪的场景
     */
    public OutboundMessage(String channel, String chatId, String content, String sessionKey) {
        this.channel = channel;
        this.chatId = chatId;
        this.content = content;
        this.sessionKey = sessionKey;
    }

    // Getter 和 Setter 方法

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getChatId() {
        return chatId;
    }

    public void setChatId(String chatId) {
        this.chatId = chatId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getSessionKey() {
        return sessionKey;
    }

    public void setSessionKey(String sessionKey) {
        this.sessionKey = sessionKey;
    }

    public MessageType getMessageType() {
        return messageType;
    }

    public void setMessageType(MessageType messageType) {
        this.messageType = messageType;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata;
    }

    public String getMetadata(String key) {
        return metadata != null ? metadata.get(key) : null;
    }

    public void putMetadata(String key, String value) {
        if (this.metadata == null) {
            this.metadata = new HashMap<>();
        }
        this.metadata.put(key, value);
    }

    @Override
    public String toString() {
        return "OutboundMessage{" +
                "channel='" + channel + '\'' +
                ", chatId='" + chatId + '\'' +
                ", sessionKey='" + sessionKey + '\'' +
                ", messageType=" + messageType +
                ", contentLength=" + (content != null ? content.length() : 0) +
                ", createdAt=" + createdAt +
                '}';
    }
}
