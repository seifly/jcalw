package cn.seifly.jclaw.web.controller;

import cn.seifly.jclaw.channels.Channel;
import cn.seifly.jclaw.channels.ChannelManager;
import cn.seifly.jclaw.channels.WechatChannel;
import cn.seifly.jclaw.config.ChannelsConfig;
import cn.seifly.jclaw.config.Config;
import cn.seifly.jclaw.logger.JClawLogger;
import cn.seifly.jclaw.web.WebUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 通道管理 API 控制器
 * 
 * 提供通道配置的查询和更新功能。
 */
@RestController
@RequestMapping("/api/channels")
@CrossOrigin(origins = "${jclaw.gateway.cors-origin:*}", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.PUT, RequestMethod.OPTIONS})
public class ChannelsController {
    
    private static final JClawLogger logger = JClawLogger.getLogger("web");
    
    @Autowired
    private Config config;

    @Autowired(required = false)
    private ChannelManager channelManager;
    
    /**
     * 获取所有通道的名称及启用状态列表
     * 
     * @return 通道列表
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getChannels() {
        List<Map<String, Object>> channels = new ArrayList<>();
        ChannelsConfig cc = config.getChannels();
        
        addChannelInfo(channels, "telegram", cc.getTelegram().isEnabled());
        addChannelInfo(channels, "discord", cc.getDiscord().isEnabled());
        addChannelInfo(channels, "whatsapp", cc.getWhatsapp().isEnabled());
        addChannelInfo(channels, "wechat", cc.getWechat().isEnabled());
        addChannelInfo(channels, "feishu", cc.getFeishu().isEnabled());
        addChannelInfo(channels, "dingtalk", cc.getDingtalk().isEnabled());
        addChannelInfo(channels, "qq", cc.getQq().isEnabled());
        addChannelInfo(channels, "maixcam", cc.getMaixcam().isEnabled());
        
        return ResponseEntity.ok(channels);
    }
    
    /**
     * 获取指定通道的详细配置
     * 
     * @param name 通道名称
     * @return 通道详情
     */
    @GetMapping("/{name}")
    public ResponseEntity<Map<String, Object>> getChannelDetail(@PathVariable("name") String name) {
        Map<String, Object> detail = getChannelDetailMap(name);
        
        if (detail != null) {
            return ResponseEntity.ok(detail);
        } else {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Channel not found");
            return ResponseEntity.status(404).body(error);
        }
    }
    
    /**
     * 更新指定通道的配置
     * 
     * @param name 通道名称
     * @param request 包含更新字段的请求体
     * @return 更新结果
     */
    @PutMapping("/{name}")
    public ResponseEntity<Map<String, Object>> updateChannel(
            @PathVariable("name") String name,
            @RequestBody Map<String, Object> request) {
        
        boolean success = updateChannelConfig(name, request);
        
        if (success) {
            WebUtils.saveConfig(config, logger);
            
            // 动态启用/禁用通道
            if (request.containsKey("enabled")) {
                boolean enabled = (Boolean) request.get("enabled");
                handleChannelEnableChange(name, enabled);
            }
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "Channel updated");
            
            return ResponseEntity.ok(result);
        } else {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Update failed");
            return ResponseEntity.status(400).body(error);
        }
    }
    
    /**
     * 处理通道启用状态变化
     * 
     * 当通过API更新通道的启用状态时，动态启动或停止通道。
     * 
     * @param name 通道名称
     * @param enabled 新的启用状态
     */
    private void handleChannelEnableChange(String name, boolean enabled) {
        if (channelManager == null) {
            logger.warn("Channel manager not available, cannot dynamically start/stop channel", 
                    Map.of("channel", name));
            return;
        }
        
        if (enabled) {
            // 启用通道
            channelManager.getChannel(name).ifPresent(channel -> {
                if (!channel.isRunning()) {
                    boolean started = channelManager.startChannel(name);
                    if (started) {
                        logger.info("Channel started dynamically", Map.of("channel", name));
                    }
                } else {
                    logger.info("Channel is already running", Map.of("channel", name));
                }
            });
            
            // 如果通道不存在（应用启动时未启用），记录警告
            if (channelManager.getChannel(name).isEmpty()) {
                logger.warn("Channel not initialized, please restart the application to enable it", 
                        Map.of("channel", name));
            }
        } else {
            // 禁用通道
            channelManager.getChannel(name).ifPresent(channel -> {
                if (channel.isRunning()) {
                    boolean stopped = channelManager.stopChannel(name);
                    if (stopped) {
                        logger.info("Channel stopped dynamically", Map.of("channel", name));
                    }
                } else {
                    logger.info("Channel is not running", Map.of("channel", name));
                }
            });
        }
    }

    /**
     * 获取微信扫码登录状态。
     * <p>
     * 当登录状态为 "failed" 或 "expired" 时，自动重启通道以重新生成二维码。
     * </p>
     */
    @GetMapping("/wechat/login")
    public ResponseEntity<Map<String, Object>> getWechatLoginStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("enabled", true);

        if (channelManager == null) {
            status.put("running", false);
            status.put("loggedIn", false);
            status.put("state", "unavailable");
            status.put("error", "Channel manager unavailable");
            return ResponseEntity.ok(status);
        }

        Channel channel = channelManager.getChannel("wechat").orElse(null);
        
        if (channel instanceof WechatChannel wechatChannel) {
            Map<String, Object> currentStatus = wechatChannel.getLoginStatus();
            String state = (String) currentStatus.get("state");
            String botId = (String) currentStatus.get("botId");
            Boolean loggedIn = (Boolean) currentStatus.get("loggedIn");
            
            boolean needRestart = "failed".equals(state) 
                    || "expired".equals(state)
                    || (loggedIn != null && loggedIn && (botId == null || botId.isEmpty()));
            
            if (needRestart) {
                logger.info("检测到微信登录状态异常（state={}, botId={}），正在重启通道以重新生成二维码", 
                        Map.of("state", state, "botId", botId));
                try {
                    config.getChannels().getWechat().setResumeContextJson(null);
                    WebUtils.saveConfig(config, logger);
                    logger.info("已清除微信 ResumeContext 配置");
                    
                    channelManager.stopChannel("wechat");
                    channelManager.unregisterChannel("wechat");
                    channel = null;
                } catch (Exception e) {
                    logger.warn("停止微信通道失败", Map.of("error", e.getMessage()));
                }
            }
        }

        if (channel == null) {
            try {
                channel = channelManager.ensureWechatChannel(config.getChannels().getWechat());
                config.getChannels().getWechat().setEnabled(true);
                WebUtils.saveConfig(config, logger);
                logger.info("微信通道已启用，配置已保存到 config.json");
            } catch (Exception e) {
                status.put("running", false);
                status.put("loggedIn", false);
                status.put("state", "failed");
                status.put("error", "启动微信通道失败: " + e.getMessage());
                return ResponseEntity.ok(status);
            }
        }

        if (channel instanceof WechatChannel wechatChannel) {
            status.putAll(wechatChannel.getLoginStatus());
            return ResponseEntity.ok(status);
        }

        status.put("running", false);
        status.put("loggedIn", false);
        status.put("state", "unavailable");
        status.put("error", "微信通道不可用");
        return ResponseEntity.ok(status);
    }
    
    // ==================== 私有辅助方法 ====================
    
    /**
     * 向 channels 列表追加一个包含 name 与 enabled 字段的 Map。
     */
    private void addChannelInfo(List<Map<String, Object>> channels, String name, boolean enabled) {
        Map<String, Object> channel = new HashMap<>();
        channel.put("name", name);
        boolean running = isChannelRunning(name);
        channel.put("enabled", enabled || running);
        channel.put("running", running);
        channels.add(channel);
    }

    private boolean isChannelRunning(String name) {
        if (channelManager == null) {
            return false;
        }
        return channelManager.getChannel(name)
                .map(Channel::isRunning)
                .orElse(false);
    }
    
    /**
     * 根据通道名获取详情 Map。
     * 不支持的通道名返回 null。
     */
    private Map<String, Object> getChannelDetailMap(String name) {
        Map<String, Object> detail = new HashMap<>();
        detail.put("name", name);
        ChannelsConfig cc = config.getChannels();
        
        return switch (name) {
            case "telegram" -> {
                detail.put("enabled", cc.getTelegram().isEnabled());
                detail.put("token", WebUtils.maskSecret(cc.getTelegram().getToken()));
                detail.put("allowFrom", cc.getTelegram().getAllowFrom());
                yield detail;
            }
            case "discord" -> {
                detail.put("enabled", cc.getDiscord().isEnabled());
                detail.put("token", WebUtils.maskSecret(cc.getDiscord().getToken()));
                detail.put("allowFrom", cc.getDiscord().getAllowFrom());
                yield detail;
            }
            case "feishu" -> {
                detail.put("enabled", cc.getFeishu().isEnabled());
                detail.put("appId", cc.getFeishu().getAppId());
                detail.put("appSecret", WebUtils.maskSecret(cc.getFeishu().getAppSecret()));
                detail.put("encryptKey", WebUtils.maskSecret(cc.getFeishu().getEncryptKey()));
                detail.put("verificationToken", WebUtils.maskSecret(cc.getFeishu().getVerificationToken()));
                detail.put("connectionMode", cc.getFeishu().getConnectionMode());
                detail.put("allowFrom", cc.getFeishu().getAllowFrom());
                yield detail;
            }
            case "wechat" -> {
                detail.put("enabled", cc.getWechat().isEnabled());
                detail.put("pollIntervalMs", cc.getWechat().getPollIntervalMs());
                detail.put("loginTimeoutSeconds", cc.getWechat().getLoginTimeoutSeconds());
                detail.put("botToken", WebUtils.maskSecret(cc.getWechat().getBotToken()));
                detail.put("allowFrom", cc.getWechat().getAllowFrom());
                yield detail;
            }
            case "dingtalk" -> {
                detail.put("enabled", cc.getDingtalk().isEnabled());
                detail.put("clientId", cc.getDingtalk().getClientId());
                detail.put("clientSecret", WebUtils.maskSecret(cc.getDingtalk().getClientSecret()));
                detail.put("webhook", cc.getDingtalk().getWebhook());
                detail.put("connectionMode", cc.getDingtalk().getConnectionMode());
                detail.put("allowFrom", cc.getDingtalk().getAllowFrom());
                yield detail;
            }
            case "qq" -> {
                detail.put("enabled", cc.getQq().isEnabled());
                detail.put("appId", cc.getQq().getAppId());
                detail.put("appSecret", WebUtils.maskSecret(cc.getQq().getAppSecret()));
                detail.put("allowFrom", cc.getQq().getAllowFrom());
                yield detail;
            }
            case "whatsapp" -> {
                detail.put("enabled", cc.getWhatsapp().isEnabled());
                detail.put("bridgeUrl", cc.getWhatsapp().getBridgeUrl());
                detail.put("allowFrom", cc.getWhatsapp().getAllowFrom());
                yield detail;
            }
            case "maixcam" -> {
                detail.put("enabled", cc.getMaixcam().isEnabled());
                detail.put("host", cc.getMaixcam().getHost());
                detail.put("port", cc.getMaixcam().getPort());
                detail.put("allowFrom", cc.getMaixcam().getAllowFrom());
                yield detail;
            }
            default -> null;
        };
    }
    
    /**
     * 根据通道名将请求中的字段写入对应的配置对象。
     * 不支持的通道名返回 false。
     */
    private boolean updateChannelConfig(String name, Map<String, Object> request) {
        ChannelsConfig cc = config.getChannels();
        
        return switch (name) {
            case "telegram" -> { updateTelegramConfig(cc, request); yield true; }
            case "discord" -> { updateDiscordConfig(cc, request); yield true; }
            case "wechat" -> { updateWechatConfig(cc, request); yield true; }
            case "feishu" -> { updateFeishuConfig(cc, request); yield true; }
            case "dingtalk" -> { updateDingtalkConfig(cc, request); yield true; }
            case "qq" -> { updateQQConfig(cc, request); yield true; }
            case "whatsapp" -> { updateWhatsappConfig(cc, request); yield true; }
            case "maixcam" -> { updateMaixcamConfig(cc, request); yield true; }
            default -> false;
        };
    }
    
    /** 更新 Telegram 配置：所有字段。 */
    private void updateTelegramConfig(ChannelsConfig cc, Map<String, Object> request) {
        if (request.containsKey("enabled")) {
            cc.getTelegram().setEnabled((Boolean) request.get("enabled"));
        }
        if (request.containsKey("token")) {
            String token = (String) request.get("token");
            if (!WebUtils.isSecretMasked(token)) {
                cc.getTelegram().setToken(token);
            }
        }
        if (request.containsKey("allowFrom")) {
            @SuppressWarnings("unchecked")
            List<String> allowFrom = (List<String>) request.get("allowFrom");
            cc.getTelegram().setAllowFrom(allowFrom);
        }
    }
    
    /** 更新 Discord 配置：所有字段。 */
    private void updateDiscordConfig(ChannelsConfig cc, Map<String, Object> request) {
        if (request.containsKey("enabled")) {
            cc.getDiscord().setEnabled((Boolean) request.get("enabled"));
        }
        if (request.containsKey("token")) {
            String token = (String) request.get("token");
            if (!WebUtils.isSecretMasked(token)) {
                cc.getDiscord().setToken(token);
            }
        }
        if (request.containsKey("allowFrom")) {
            @SuppressWarnings("unchecked")
            List<String> allowFrom = (List<String>) request.get("allowFrom");
            cc.getDiscord().setAllowFrom(allowFrom);
        }
    }

    /** 更新微信配置：所有字段。 */
    private void updateWechatConfig(ChannelsConfig cc, Map<String, Object> request) {
        if (request.containsKey("enabled")) {
            cc.getWechat().setEnabled((Boolean) request.get("enabled"));
        }
        if (request.containsKey("pollIntervalMs")) {
            cc.getWechat().setPollIntervalMs(toInt(request.get("pollIntervalMs"), cc.getWechat().getPollIntervalMs()));
        }
        if (request.containsKey("loginTimeoutSeconds")) {
            cc.getWechat().setLoginTimeoutSeconds(toInt(request.get("loginTimeoutSeconds"), cc.getWechat().getLoginTimeoutSeconds()));
        }
        if (request.containsKey("botToken")) {
            String botToken = (String) request.get("botToken");
            if (!WebUtils.isSecretMasked(botToken)) {
                cc.getWechat().setBotToken(botToken);
            }
        }
        if (request.containsKey("allowFrom")) {
            @SuppressWarnings("unchecked")
            List<String> allowFrom = (List<String>) request.get("allowFrom");
            cc.getWechat().setAllowFrom(allowFrom);
        }
    }
    
    /** 更新飞书配置：所有字段。 */
    private void updateFeishuConfig(ChannelsConfig cc, Map<String, Object> request) {
        if (request.containsKey("enabled")) {
            cc.getFeishu().setEnabled((Boolean) request.get("enabled"));
        }
        if (request.containsKey("appId")) {
            cc.getFeishu().setAppId((String) request.get("appId"));
        }
        if (request.containsKey("appSecret")) {
            String appSecret = (String) request.get("appSecret");
            if (!WebUtils.isSecretMasked(appSecret)) {
                cc.getFeishu().setAppSecret(appSecret);
            }
        }
        if (request.containsKey("encryptKey")) {
            String encryptKey = (String) request.get("encryptKey");
            if (!WebUtils.isSecretMasked(encryptKey)) {
                cc.getFeishu().setEncryptKey(encryptKey);
            }
        }
        if (request.containsKey("verificationToken")) {
            String verificationToken = (String) request.get("verificationToken");
            if (!WebUtils.isSecretMasked(verificationToken)) {
                cc.getFeishu().setVerificationToken(verificationToken);
            }
        }
        if (request.containsKey("connectionMode")) {
            cc.getFeishu().setConnectionMode((String) request.get("connectionMode"));
        }
        if (request.containsKey("allowFrom")) {
            @SuppressWarnings("unchecked")
            List<String> allowFrom = (List<String>) request.get("allowFrom");
            cc.getFeishu().setAllowFrom(allowFrom);
        }
    }
    
    /** 更新钉钉配置：所有字段。 */
    private void updateDingtalkConfig(ChannelsConfig cc, Map<String, Object> request) {
        if (request.containsKey("enabled")) {
            cc.getDingtalk().setEnabled((Boolean) request.get("enabled"));
        }
        if (request.containsKey("clientId")) {
            cc.getDingtalk().setClientId((String) request.get("clientId"));
        }
        if (request.containsKey("clientSecret")) {
            String clientSecret = (String) request.get("clientSecret");
            if (!WebUtils.isSecretMasked(clientSecret)) {
                cc.getDingtalk().setClientSecret(clientSecret);
            }
        }
        if (request.containsKey("webhook")) {
            cc.getDingtalk().setWebhook((String) request.get("webhook"));
        }
        if (request.containsKey("connectionMode")) {
            cc.getDingtalk().setConnectionMode((String) request.get("connectionMode"));
        }
        if (request.containsKey("allowFrom")) {
            @SuppressWarnings("unchecked")
            List<String> allowFrom = (List<String>) request.get("allowFrom");
            cc.getDingtalk().setAllowFrom(allowFrom);
        }
    }
    
    /** 更新 QQ 配置：所有字段。 */
    private void updateQQConfig(ChannelsConfig cc, Map<String, Object> request) {
        if (request.containsKey("enabled")) {
            cc.getQq().setEnabled((Boolean) request.get("enabled"));
        }
        if (request.containsKey("appId")) {
            cc.getQq().setAppId((String) request.get("appId"));
        }
        if (request.containsKey("appSecret")) {
            String appSecret = (String) request.get("appSecret");
            if (!WebUtils.isSecretMasked(appSecret)) {
                cc.getQq().setAppSecret(appSecret);
            }
        }
        if (request.containsKey("allowFrom")) {
            @SuppressWarnings("unchecked")
            List<String> allowFrom = (List<String>) request.get("allowFrom");
            cc.getQq().setAllowFrom(allowFrom);
        }
    }
    
    /** 更新 WhatsApp 配置：所有字段。 */
    private void updateWhatsappConfig(ChannelsConfig cc, Map<String, Object> request) {
        if (request.containsKey("enabled")) {
            cc.getWhatsapp().setEnabled((Boolean) request.get("enabled"));
        }
        if (request.containsKey("bridgeUrl")) {
            cc.getWhatsapp().setBridgeUrl((String) request.get("bridgeUrl"));
        }
        if (request.containsKey("allowFrom")) {
            @SuppressWarnings("unchecked")
            List<String> allowFrom = (List<String>) request.get("allowFrom");
            cc.getWhatsapp().setAllowFrom(allowFrom);
        }
    }
    
    /** 更新 MaixCam 配置：所有字段。 */
    private void updateMaixcamConfig(ChannelsConfig cc, Map<String, Object> request) {
        if (request.containsKey("enabled")) {
            cc.getMaixcam().setEnabled((Boolean) request.get("enabled"));
        }
        if (request.containsKey("host")) {
            cc.getMaixcam().setHost((String) request.get("host"));
        }
        if (request.containsKey("port")) {
            cc.getMaixcam().setPort(toInt(request.get("port"), cc.getMaixcam().getPort()));
        }
        if (request.containsKey("allowFrom")) {
            @SuppressWarnings("unchecked")
            List<String> allowFrom = (List<String>) request.get("allowFrom");
            cc.getMaixcam().setAllowFrom(allowFrom);
        }
    }

    private int toInt(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String str && !str.isBlank()) {
            try {
                return Integer.parseInt(str);
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }
}
