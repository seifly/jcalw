package cn.seifly.jclaw.web.controller;

import cn.seifly.jclaw.collaboration.AgentMessage;
import cn.seifly.jclaw.collaboration.CollaborationRecord;
import cn.seifly.jclaw.config.Config;
import cn.seifly.jclaw.logger.JClawLogger;
import cn.seifly.jclaw.session.Session;
import cn.seifly.jclaw.session.SessionManager;
import cn.seifly.jclaw.session.ToolCallRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话管理 API 控制器
 * 
 * 提供会话的增删改查功能，用于 Web 控制台的会话管理。
 */
@RestController
@RequestMapping("/api/sessions")
@CrossOrigin(origins = "${jclaw.gateway.cors-origin:*}", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.DELETE, RequestMethod.OPTIONS})
public class SessionsController {
    
    private static final JClawLogger logger = JClawLogger.getLogger("web");
    
    @Autowired
    private Config config;
    
    @Autowired
    private SessionManager sessionManager;
    
    @Value("${jclaw.agent.workspace:~/.jclaw/workspace}")
    private String workspacePath;
    
    /**
     * 获取所有会话列表
     * 
     * @return 会话列表，包含每个会话的 key、messageCount 和 firstMessage
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getSessions() {
        List<Map<String, Object>> sessions = new ArrayList<>();
        
        for (String key : sessionManager.getSessionKeys()) {
            var history = sessionManager.getHistory(key);
            Map<String, Object> session = new HashMap<>();
            session.put("key", key);
            session.put("messageCount", history.size());
            
            // 取第一条 user 消息作为会话预览标题
            String firstMessage = history.stream()
                    .filter(m -> "user".equals(m.getRole()) && m.getContent() != null && !m.getContent().isBlank())
                    .findFirst()
                    .map(m -> m.getContent().length() > 15 ? m.getContent().substring(0, 15) + "…" : m.getContent())
                    .orElse("");
            session.put("firstMessage", firstMessage);
            sessions.add(session);
        }
        
        return ResponseEntity.ok(sessions);
    }
    
    /**
     * 创建新会话
     * 
     * @param requestBody 包含 sessionKey 的请求体
     * @return 创建的会话信息
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createSession(@RequestBody Map<String, String> requestBody) {
        String sessionKey = requestBody.get("sessionKey");
        
        if (sessionKey == null || sessionKey.isBlank()) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "sessionKey is required");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }
        
        Session session = sessionManager.getOrCreate(sessionKey);
        sessionManager.save(session);
        
        Map<String, Object> result = new HashMap<>();
        result.put("key", sessionKey);
        result.put("messageCount", 0);
        
        return ResponseEntity.ok(result);
    }
    
    /**
     * 获取指定会话的详情
     * 
     * @param key 会话 key（URL 编码）
     * @return 会话详情，包括消息历史、工具调用记录等
     */
    @GetMapping("/{key}")
    public ResponseEntity<List<Map<String, Object>>> getSessionDetail(@PathVariable("key") String key) {
        String decodedKey = URLDecoder.decode(key, StandardCharsets.UTF_8);
        
        var history = sessionManager.getHistory(decodedKey);
        var toolCallRecords = sessionManager.getToolCallRecords(decodedKey);
        var summary = sessionManager.getSummary(decodedKey);
        
        // 按 messageIndex（assistant 消息在 history 中的绝对位置索引）分组工具调用记录
        Map<Integer, List<ToolCallRecord>> recordsByIndex = new HashMap<>();
        for (ToolCallRecord record : toolCallRecords) {
            recordsByIndex
                    .computeIfAbsent(record.getMessageIndex(), idx -> new ArrayList<>())
                    .add(record);
        }
        
        List<Map<String, Object>> messages = new ArrayList<>();
        
        // 如果会话有摘要（说明历史已被压缩），在消息列表最前面插入一条虚拟摘要消息，
        // 前端检测到 role=summary 时渲染为摘要提示卡片，告知用户前面有内容已被压缩
        if (summary != null && !summary.isBlank()) {
            Map<String, Object> summaryMsg = new HashMap<>();
            summaryMsg.put("role", "summary");
            summaryMsg.put("content", summary);
            messages.add(summaryMsg);
        }
        
        int msgIdx = 0;
        for (var msg : history) {
            Map<String, Object> m = new HashMap<>();
            m.put("role", msg.getRole());
            m.put("content", msg.getContent() != null ? msg.getContent() : "");
            
            // 添加图片字段（多模态支持）
            if (msg.hasImages()) {
                m.put("images", msg.getImages());
            }
            
            // assistant 消息：按绝对位置索引附带其触发的工具调用记录
            if ("assistant".equals(msg.getRole())) {
                var records = recordsByIndex.get(msgIdx);
                if (records != null && !records.isEmpty()) {
                    List<Map<String, Object>> toolCallsArray = new ArrayList<>();
                    for (ToolCallRecord record : records) {
                        Map<String, Object> r = new HashMap<>();
                        r.put("toolName", record.getToolName());
                        r.put("argsSummary", record.getArgsSummary());
                        r.put("resultSummary", record.getResultSummary());
                        r.put("success", record.isSuccess());
                        
                        // collaborate 工具调用：附带协同过程详情
                        if ("collaborate".equals(record.getToolName())) {
                            Map<String, Object> collaborationDetail = buildCollaborationDetail(record.getArgsSummary());
                            if (collaborationDetail != null) {
                                r.put("collaborationDetail", collaborationDetail);
                            }
                        }
                        
                        toolCallsArray.add(r);
                    }
                    m.put("toolCallRecords", toolCallsArray);
                }
            }
            
            messages.add(m);
            msgIdx++;
        }
        
        return ResponseEntity.ok(messages);
    }
    
    /**
     * 删除指定会话
     * 
     * @param key 会话 key（URL 编码）
     * @return 删除结果
     */
    @DeleteMapping("/{key}")
    public ResponseEntity<Map<String, Object>> deleteSession(@PathVariable("key") String key) {
        String decodedKey = URLDecoder.decode(key, StandardCharsets.UTF_8);
        
        sessionManager.deleteSession(decodedKey);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "Session deleted");
        
        return ResponseEntity.ok(result);
    }
    
    /**
     * 构建协同过程详情
     * 
     * @param argsSummary 工具调用参数摘要
     * @return 协同详情，如果没有找到则返回 null
     */
    private Map<String, Object> buildCollaborationDetail(String argsSummary) {
        String collaborationDir = workspacePath != null
                ? Paths.get(workspacePath, "collaboration").toString() : null;
        
        if (collaborationDir == null) {
            logger.info("collaborationDir is null, skip", Map.of());
            return null;
        }
        
        logger.info("Loading collaboration records", Map.of("dir", collaborationDir));
        List<CollaborationRecord> allRecords = CollaborationRecord.loadAll(collaborationDir);
        logger.info("Loaded collaboration records", Map.of("count", allRecords.size(), "dir", collaborationDir));
        
        if (allRecords.isEmpty()) {
            return null;
        }
        
        // 从 argsSummary 中提取 topic 关键词进行匹配
        // argsSummary 格式示例: {mode=debate, topic=AI 会毁灭人类, roles=[...]}
        CollaborationRecord matchedRecord = null;
        for (CollaborationRecord record : allRecords) {
            if (record.getGoal() != null && argsSummary != null
                    && argsSummary.contains(record.getGoal())) {
                matchedRecord = record;
                break;
            }
        }
        
        // 降级：取最新的协同记录（按 endTime 排序）
        if (matchedRecord == null && !allRecords.isEmpty()) {
            matchedRecord = allRecords.stream()
                    .max(java.util.Comparator.comparingLong(CollaborationRecord::getEndTime))
                    .orElse(null);
        }
        
        if (matchedRecord == null) {
            return null;
        }
        
        return buildCollaborationDetailMap(matchedRecord);
    }
    
    /**
     * 将 CollaborationRecord 构建为 Map，包含模式、目标、参与者、
     * 多 Agent 对话历史和统计指标。
     */
    private Map<String, Object> buildCollaborationDetailMap(CollaborationRecord record) {
        Map<String, Object> detail = new HashMap<>();
        detail.put("mode", record.getMode());
        detail.put("goal", record.getGoal() != null ? record.getGoal() : "");
        detail.put("conclusion", record.getConclusion() != null ? record.getConclusion() : "");
        detail.put("totalRounds", record.getTotalRounds());
        detail.put("status", record.getStatus() != null ? record.getStatus() : "");
        detail.put("startTime", record.getStartTime());
        detail.put("endTime", record.getEndTime());
        
        // 参与者列表
        if (record.getParticipants() != null && !record.getParticipants().isEmpty()) {
            detail.put("participants", record.getParticipants());
        }
        
        // 多 Agent 对话历史
        if (record.getMessages() != null && !record.getMessages().isEmpty()) {
            List<Map<String, Object>> agentMessages = new ArrayList<>();
            for (AgentMessage agentMsg : record.getMessages()) {
                Map<String, Object> agentMsgMap = new HashMap<>();
                agentMsgMap.put("agentId", agentMsg.getAgentId() != null ? agentMsg.getAgentId() : "");
                agentMsgMap.put("agentRole", agentMsg.getAgentRole() != null ? agentMsg.getAgentRole() : "");
                agentMsgMap.put("content", agentMsg.getContent() != null ? agentMsg.getContent() : "");
                agentMsgMap.put("timestamp", agentMsg.getTimestamp());
                if (agentMsg.getMessageType() != null) {
                    agentMsgMap.put("messageType", agentMsg.getMessageType().name());
                }
                if (agentMsg.getTargetRole() != null) {
                    agentMsgMap.put("targetRole", agentMsg.getTargetRole());
                }
                agentMessages.add(agentMsgMap);
            }
            detail.put("agentMessages", agentMessages);
        }
        
        // 统计指标
        if (record.getMetrics() != null && !record.getMetrics().isEmpty()) {
            detail.put("metrics", record.getMetrics());
        }
        
        return detail;
    }
}