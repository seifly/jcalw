package cn.seifly.jclaw.web.controller;

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
    
    // ==================== 私有辅助方法 ====================
    
    /**
     * 向 channels 列表追加一个包含 name 与 enabled 字段的 Map。
     */
    private void addChannelInfo(List<Map<String, Object>> channels, String name, boolean enabled) {
        Map<String, Object> channel = new HashMap<>();
        channel.put("name", name);
        channel.put("enabled", enabled);
        channels.add(channel);
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
                detail.put("allowFrom", cc.getFeishu().getAllowFrom());
                yield detail;
            }
            case "dingtalk" -> {
                detail.put("enabled", cc.getDingtalk().isEnabled());
                detail.put("clientId", cc.getDingtalk().getClientId());
                detail.put("clientSecret", WebUtils.maskSecret(cc.getDingtalk().getClientSecret()));
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
            case "feishu" -> { updateFeishuConfig(cc, request); yield true; }
            case "dingtalk" -> { updateDingtalkConfig(cc, request); yield true; }
            case "qq" -> { updateQQConfig(cc, request); yield true; }
            default -> false;
        };
    }
    
    /** 更新 Telegram 配置：enabled 及 Token（已掩码时跳过）。 */
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
    }
    
    /** 更新 Discord 配置：enabled 及 Token（已掩码时跳过）。 */
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
    }
    
    /** 更新飞书配置：enabled、appId 及 appSecret（已掩码时跳过）。 */
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
    }
    
    /** 更新钉钉配置：enabled、clientId 及 clientSecret（已掩码时跳过）。 */
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
    }
    
    /** 更新 QQ 配置：enabled、appId 及 appSecret（已掩码时跳过）。 */
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
    }
}