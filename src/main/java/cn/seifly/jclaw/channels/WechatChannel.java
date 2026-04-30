package cn.seifly.jclaw.channels;

import cn.seifly.jclaw.bus.InboundMessage;
import cn.seifly.jclaw.bus.MessageBus;
import cn.seifly.jclaw.bus.OutboundMessage;
import cn.seifly.jclaw.config.ChannelsConfig;
import cn.seifly.jclaw.config.Config;
import cn.seifly.jclaw.config.ConfigLoader;
import cn.seifly.jclaw.util.StringUtils;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 微信 iLink 通道。
 *
 * <p>SDK 的接收模型是扫码登录后由业务主动调用 getUpdates() 拉取消息，
 * 因此这里启动一个单线程消息泵，并在 SDK listener 中发布到 jclaw 总线。</p>
 * 
 * <p>登录状态恢复机制：
 * - 登录成功后，使用 client.exportResumeContext() 导出恢复上下文
 * - 将上下文序列化为 JSON 并保存到配置文件
 * - 下次启动时，从配置读取并反序列化 ResumeContext，使用 ILinkClient.builder().resumeContext() 恢复登录</p>
 */
public class WechatChannel extends BaseChannel {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Config rootConfig;
    private final ChannelsConfig.WechatConfig config;
    private final Set<Long> handledMessageIds = ConcurrentHashMap.newKeySet();
    private final AtomicReference<String> qrCodeContent = new AtomicReference<>("");
    private final AtomicReference<String> qrCodeImage = new AtomicReference<>("");
    private final AtomicReference<String> botId = new AtomicReference<>("");
    private final AtomicReference<String> loginError = new AtomicReference<>("");
    private final AtomicReference<String> loginState = new AtomicReference<>("not_started");

    private ILinkClient client;
    private ScheduledExecutorService messagePump;

    public WechatChannel(Config rootConfig, ChannelsConfig.WechatConfig config, MessageBus bus) {
        super("wechat", bus, config.getAllowFrom());
        this.rootConfig = rootConfig;
        this.config = config;
    }

    @Override
    public synchronized void start() {
        if (isRunning()) {
            return;
        }

        try {
            if (tryLoginWithResumeContext()) {
                startLoginTimeoutWatcher();
                startMessagePump();
                setRunning(true);
                logger.info("微信通道已启动，使用 ResumeContext 恢复登录");
                return;
            }
            
            ILinkConfig ilinkConfig = ILinkConfig.builder()
                    .connectTimeoutMs(35000)
                    .readTimeoutMs(35000)
                    .writeTimeoutMs(35000)
                    .httpMaxRetries(3)
                    .heartbeatEnabled(false)
                    .build();

            client = ILinkClient.builder()
                    .config(ilinkConfig)
                    .onLogin(new OnLoginListener() {
                        @Override
                        public void onLoginSuccess(LoginContext context) {
                            botId.set(context != null ? context.getBotId() : "");
                            loginState.set("logged_in");
                            loginError.set("");
                            qrCodeContent.set("");
                            qrCodeImage.set("");
                            logger.info("微信登录成功", Map.of("bot_id", botId.get()));
                            
                            exportAndSaveResumeContext();
                        }

                        @Override
                        public void onLoginFailure(Throwable throwable) {
                            loginState.set("failed");
                            loginError.set(throwable != null ? throwable.getMessage() : "unknown login failure");
                            logger.error("微信登录失败", Map.of("error", loginError.get()));
                        }
                    })
                    .onMessage(new OnMessageListener() {
                        @Override
                        public void onMessages(List<WeixinMessage> messages) {
                            handleMessages(messages);
                        }
                    })
                    .build();

            String qr = client.executeLogin();
            qrCodeContent.set(qr != null ? qr : "");
            qrCodeImage.set(renderQrCode(qrCodeContent.get()));
            loginState.set("waiting_scan");

            startLoginTimeoutWatcher();
            startMessagePump();
            setRunning(true);

            logger.info("微信通道已启动，等待扫码登录");
        } catch (Exception e) {
            stop();
            throw new ChannelException("启动微信通道失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void stop() {
        setRunning(false);

        if (messagePump != null) {
            messagePump.shutdownNow();
            messagePump = null;
        }

        if (client != null) {
            try {
                if (!client.isLoggedIn()) {
                    client.cancelLogin();
                }
            } catch (Exception e) {
                logger.debug("取消微信登录失败", Map.of("error", e.getMessage()));
            }
            try {
                client.close();
            } catch (Exception e) {
                logger.warn("关闭微信客户端失败", Map.of("error", e.getMessage()));
            }
            client = null;
        }

        qrCodeContent.set("");
        qrCodeImage.set("");
        botId.set("");
        loginState.set("stopped");
    }

    @Override
    public void send(OutboundMessage message) {
        if (!isRunning() || client == null || !client.isLoggedIn()) {
            throw new ChannelException("微信通道未登录");
        }

        String userId = message.getChatId();
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("微信用户 ID 为空");
        }

        try {
            client.sendText(userId, message.getContent());
            logger.debug("微信消息发送成功", Map.of("user_id", userId));
        } catch (Exception e) {
            throw new ChannelException("发送微信消息失败: " + e.getMessage(), e);
        }
    }

    public Map<String, Object> getLoginStatus() {
        Map<String, Object> status = new HashMap<>();
        boolean loggedIn = client != null && client.isLoggedIn();
        status.put("running", isRunning());
        status.put("loggedIn", loggedIn);
        status.put("state", loggedIn ? "logged_in" : loginState.get());
        status.put("botId", botId.get());
        status.put("qrCodeContent", qrCodeContent.get());
        status.put("qrCodeImage", qrCodeImage.get());
        status.put("error", loginError.get());
        return status;
    }

    private void startLoginTimeoutWatcher() {
        Thread watcher = new Thread(() -> {
            try {
                client.getLoginFuture().get(config.getLoginTimeoutSeconds(), TimeUnit.SECONDS);
            } catch (Exception e) {
                if (client != null && !client.isLoggedIn()) {
                    loginState.set("expired");
                    loginError.set("二维码登录超时，请重启微信通道后重新扫码");
                    logger.warn("微信二维码登录超时");
                }
            }
        }, "wechat-login-watcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    private void startMessagePump() {
        long intervalMs = Math.max(500L, config.getPollIntervalMs());
        messagePump = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "wechat-message-pump");
            thread.setDaemon(true);
            return thread;
        });

        messagePump.scheduleWithFixedDelay(() -> {
            if (client == null || !client.isLoggedIn()) {
                return;
            }
            try {
                client.getUpdates();
            } catch (Exception e) {
                logger.warn("拉取微信消息失败", Map.of("error", e.getMessage()));
            }
        }, 0, intervalMs, TimeUnit.MILLISECONDS);
    }

    private void handleMessages(List<WeixinMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        for (WeixinMessage message : messages) {
            if (message == null) {
                continue;
            }

            Long messageId = message.getMessage_id();
            if (messageId != null && !handledMessageIds.add(messageId)) {
                continue;
            }
            if (handledMessageIds.size() > 5000) {
                handledMessageIds.clear();
            }

            String senderId = message.getFrom_user_id();
            if (senderId == null || senderId.isEmpty()) {
                senderId = "unknown";
            }

            String content = extractText(message);
            if (content.isEmpty()) {
                content = "[非文本消息]";
            }

            Map<String, String> metadata = new HashMap<>();
            if (messageId != null) {
                metadata.put("message_id", String.valueOf(messageId));
            }
            if (message.getTo_user_id() != null) {
                metadata.put("to_user_id", message.getTo_user_id());
            }
            if (message.getContext_token() != null) {
                metadata.put("context_token", message.getContext_token());
            }

            logger.info("收到微信消息", Map.of(
                    "sender_id", senderId,
                    "preview", StringUtils.truncate(content, 80)
            ));

            InboundMessage inbound = handleMessage(senderId, senderId, content, null, metadata);
            if (inbound == null) {
                logger.warn("微信消息被拒绝（可能权限不足）", Map.of("sender_id", senderId));
            }
        }
    }

    private String extractText(WeixinMessage message) {
        if (message.getItem_list() == null) {
            return "";
        }

        StringBuilder content = new StringBuilder();
        for (MessageItem item : message.getItem_list()) {
            if (item != null && item.getText_item() != null && item.getText_item().getText() != null) {
                if (!content.isEmpty()) {
                    content.append('\n');
                }
                content.append(item.getText_item().getText());
            }
        }
        return content.toString().trim();
    }

    private String renderQrCode(String content) throws Exception {
        if (content == null || content.isEmpty()) {
            return "";
        }
        QRCodeWriter writer = new QRCodeWriter();
        BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE, 280, 280);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(matrix, "PNG", output);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
    }

    /**
     * 尝试使用已保存的 ResumeContext 恢复登录。
     * 
     * 实现流程：
     * 1. 从配置中读取 resumeContextJson
     * 2. 反序列化为 Map（因为 ResumeContext 没有默认构造函数，无法直接反序列化）
     * 3. 使用反射从 Map 创建 ResumeContext 实例
     * 4. 使用 ILinkClient.builder().resumeContext() 创建客户端
     * 
     * @return 如果成功恢复登录返回 true，否则返回 false（需要使用二维码登录）
     */
    @SuppressWarnings("unchecked")
    private boolean tryLoginWithResumeContext() {
        String resumeContextJson = config.getResumeContextJson();
        if (resumeContextJson == null || resumeContextJson.isEmpty()) {
            return false;
        }
        
        logger.info("尝试使用已保存的 ResumeContext 恢复登录");
        
        try {
            Class<?> resumeContextClass = Class.forName("com.github.wechat.ilink.sdk.core.context.ResumeContext");
            
            Map<String, Object> contextMap = OBJECT_MAPPER.readValue(resumeContextJson, Map.class);
            ResumeContext resumeContext = (ResumeContext)createResumeContextFromMap(contextMap, ResumeContext.class);
            
            if (resumeContext == null) {
                logger.error("无法从保存的配置创建 ResumeContext 实例");
                config.setResumeContextJson(null);
                saveConfig();
                return false;
            }
            
            ILinkConfig ilinkConfig = ILinkConfig.builder()
                    .connectTimeoutMs(35000)
                    .readTimeoutMs(35000)
                    .writeTimeoutMs(35000)
                    .httpMaxRetries(3)
                    .heartbeatEnabled(false)
                    .build();
            
            client = ILinkClient.builder()
                    .config(ilinkConfig)
                    .resumeContext(resumeContext)
                    .onLogin(new OnLoginListener() {
                        @Override
                        public void onLoginSuccess(LoginContext context) {
                            botId.set(context != null ? context.getBotId() : "");
                            loginState.set("logged_in");
                            loginError.set("");
                            qrCodeContent.set("");
                            qrCodeImage.set("");
                            logger.info("微信使用 ResumeContext 恢复登录成功", Map.of("bot_id", botId.get()));
                            
                            exportAndSaveResumeContext();
                        }

                        @Override
                        public void onLoginFailure(Throwable throwable) {
                            loginState.set("failed");
                            loginError.set(throwable != null ? throwable.getMessage() : "resume context 登录失败");
                            logger.error("微信使用 ResumeContext 恢复登录失败", Map.of("error", loginError.get()));
                            
                            config.setResumeContextJson(null);
                            saveConfig();
                        }
                    })
                    .onMessage(new OnMessageListener() {
                        @Override
                        public void onMessages(List<WeixinMessage> messages) {
                            handleMessages(messages);
                        }
                    })
                    .build();
            
            loginState.set("waiting_resume_login");
            return true;
            
        } catch (ClassNotFoundException e) {
            logger.error("未找到 ResumeContext 类", Map.of("error", e.getMessage()));
            config.setResumeContextJson(null);
            saveConfig();
            return false;
        } catch (Exception e) {
            logger.error("使用 ResumeContext 恢复登录失败", Map.of("error", e.getMessage()));
            config.setResumeContextJson(null);
            saveConfig();
            return false;
        }
    }

    /**
     * 导出 ResumeContext 并保存到配置文件。
     * 
     * 实现流程：
     * 1. 使用 client.exportResumeContext() 导出恢复上下文
     * 2. 使用反射提取所有字段到 Map
     * 3. 序列化为 JSON 字符串（Map 可以正常序列化）
     * 4. 保存到配置并持久化到配置文件
     */
    private void exportAndSaveResumeContext() {
        if (client == null) {
            return;
        }
        
        try {
            java.lang.reflect.Method exportMethod = client.getClass().getMethod("exportResumeContext");
            Object resumeContext = exportMethod.invoke(client);
            
            if (resumeContext != null) {
                Map<String, Object> contextMap = extractFieldsToMap(resumeContext);
                String json = OBJECT_MAPPER.writeValueAsString(contextMap);
                config.setResumeContextJson(json);
                saveConfig();
                logger.info("微信 ResumeContext 已保存到配置");
            }
        } catch (NoSuchMethodException e) {
            logger.debug("ILinkClient 没有 exportResumeContext 方法", Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("导出或保存 ResumeContext 失败", Map.of("error", e.getMessage()));
        }
    }

    /**
     * 使用反射提取对象的所有字段到 Map。
     * 
     * @param obj 要提取的对象
     * @return 包含所有字段名和值的 Map
     */
    private Map<String, Object> extractFieldsToMap(Object obj) {
        Map<String, Object> map = new HashMap<>();
        if (obj == null) {
            return map;
        }
        
        Class<?> clazz = obj.getClass();
        for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
            try {
                field.setAccessible(true);
                Object value = field.get(obj);
                if (value != null) {
                    map.put(field.getName(), value);
                }
            } catch (Exception e) {
                logger.debug("提取字段失败", Map.of("field", field.getName(), "error", e.getMessage()));
            }
        }
        
        return map;
    }

    /**
     * 使用反射从 Map 创建 ResumeContext 实例并设置字段。
     * 
     * @param map 包含字段名和值的 Map
     * @param clazz ResumeContext 类
     * @return 创建的 ResumeContext 实例，如果失败返回 null
     */
    private Object createResumeContextFromMap(Map<String, Object> map, Class<?> clazz) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        
        try {
            Object instance = null;
            
            java.lang.reflect.Constructor<?>[] constructors = clazz.getDeclaredConstructors();
            for (java.lang.reflect.Constructor<?> constructor : constructors) {
                try {
                    constructor.setAccessible(true);
                    Class<?>[] paramTypes = constructor.getParameterTypes();
                    Object[] params = new Object[paramTypes.length];
                    
                    for (int i = 0; i < paramTypes.length; i++) {
                        params[i] = null;
                    }
                    
                    instance = constructor.newInstance(params);
                    break;
                } catch (Exception e) {
                    logger.debug("尝试构造函数失败", Map.of("params", constructor.getParameterCount()));
                }
            }
            
            if (instance == null) {
                logger.error("无法找到合适的构造函数创建 ResumeContext 实例");
                return null;
            }
            
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                try {
                    java.lang.reflect.Field field = clazz.getDeclaredField(entry.getKey());
                    field.setAccessible(true);
                    
                    Object value = entry.getValue();
                    if (value instanceof Map) {
                        Class<?> fieldType = field.getType();
                        try {
                            String nestedJson = OBJECT_MAPPER.writeValueAsString(value);
                            value = OBJECT_MAPPER.readValue(nestedJson, fieldType);
                        } catch (Exception e) {
                            logger.debug("嵌套对象反序列化失败，尝试使用反射创建", 
                                    Map.of("field", entry.getKey(), "error", e.getMessage()));
                            value = createNestedObjectFromMap((Map<String, Object>) value, fieldType);
                        }
                    } else if (value instanceof Number) {
                        Class<?> fieldType = field.getType();
                        if (fieldType == int.class || fieldType == Integer.class) {
                            value = ((Number) value).intValue();
                        } else if (fieldType == long.class || fieldType == Long.class) {
                            value = ((Number) value).longValue();
                        } else if (fieldType == double.class || fieldType == Double.class) {
                            value = ((Number) value).doubleValue();
                        } else if (fieldType == float.class || fieldType == Float.class) {
                            value = ((Number) value).floatValue();
                        }
                    }
                    
                    field.set(instance, value);
                } catch (NoSuchFieldException e) {
                    logger.debug("字段不存在，跳过", Map.of("field", entry.getKey()));
                } catch (Exception e) {
                    logger.debug("设置字段失败", Map.of("field", entry.getKey(), "error", e.getMessage()));
                }
            }
            
            return instance;
            
        } catch (Exception e) {
            logger.error("创建 ResumeContext 实例失败", Map.of("error", e.getMessage()));
            return null;
        }
    }

    /**
     * 使用反射从 Map 创建嵌套对象实例。
     * 
     * @param map 包含字段名和值的 Map
     * @param clazz 目标类
     * @return 创建的对象实例，如果失败返回 null
     */
    private Object createNestedObjectFromMap(Map<String, Object> map, Class<?> clazz) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        
        try {
            Object instance = null;
            
            java.lang.reflect.Constructor<?>[] constructors = clazz.getDeclaredConstructors();
            for (java.lang.reflect.Constructor<?> constructor : constructors) {
                try {
                    constructor.setAccessible(true);
                    Class<?>[] paramTypes = constructor.getParameterTypes();
                    Object[] params = new Object[paramTypes.length];
                    
                    for (int i = 0; i < paramTypes.length; i++) {
                        params[i] = null;
                    }
                    
                    instance = constructor.newInstance(params);
                    break;
                } catch (Exception e) {
                }
            }
            
            if (instance == null) {
                return null;
            }
            
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                try {
                    java.lang.reflect.Field field = clazz.getDeclaredField(entry.getKey());
                    field.setAccessible(true);
                    
                    Object value = entry.getValue();
                    if (value instanceof Map) {
                        Class<?> fieldType = field.getType();
                        try {
                            String nestedJson = OBJECT_MAPPER.writeValueAsString(value);
                            value = OBJECT_MAPPER.readValue(nestedJson, fieldType);
                        } catch (Exception e) {
                            value = createNestedObjectFromMap((Map<String, Object>) value, fieldType);
                        }
                    } else if (value instanceof Number) {
                        Class<?> fieldType = field.getType();
                        if (fieldType == int.class || fieldType == Integer.class) {
                            value = ((Number) value).intValue();
                        } else if (fieldType == long.class || fieldType == Long.class) {
                            value = ((Number) value).longValue();
                        }
                    }
                    
                    field.set(instance, value);
                } catch (Exception e) {
                }
            }
            
            return instance;
            
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 保存配置到配置文件。
     */
    private void saveConfig() {
        try {
            String configPath = ConfigLoader.getConfigPath();
            ConfigLoader.save(configPath, rootConfig);
            logger.debug("微信配置已保存到文件", Map.of("path", configPath));
        } catch (Exception e) {
            logger.error("保存微信配置失败", Map.of("error", e.getMessage()));
        }
    }
}
