package cn.seifly.jclaw.channels;

import cn.seifly.jclaw.bus.InboundMessage;
import cn.seifly.jclaw.bus.MessageBus;
import cn.seifly.jclaw.bus.OutboundMessage;
import cn.seifly.jclaw.config.ChannelsConfig;
import cn.seifly.jclaw.util.StringUtils;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
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
 */
public class WechatChannel extends BaseChannel {

    private final ChannelsConfig.WechatConfig config;
    private final Set<Long> handledMessageIds = ConcurrentHashMap.newKeySet();
    private final AtomicReference<String> qrCodeContent = new AtomicReference<>("");
    private final AtomicReference<String> qrCodeImage = new AtomicReference<>("");
    private final AtomicReference<String> botId = new AtomicReference<>("");
    private final AtomicReference<String> loginError = new AtomicReference<>("");
    private final AtomicReference<String> loginState = new AtomicReference<>("not_started");

    private ILinkClient client;
    private ScheduledExecutorService messagePump;

    public WechatChannel(ChannelsConfig.WechatConfig config, MessageBus bus) {
        super("wechat", bus, config.getAllowFrom());
        this.config = config;
    }

    @Override
    public synchronized void start() {
        if (isRunning()) {
            return;
        }

        try {
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
}
