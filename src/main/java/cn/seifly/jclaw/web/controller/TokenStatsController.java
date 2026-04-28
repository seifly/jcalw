package cn.seifly.jclaw.web.controller;

import cn.seifly.jclaw.config.Config;
import cn.seifly.jclaw.logger.JClawLogger;
import cn.seifly.jclaw.tools.TokenUsageStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Token 消耗统计 API 控制器
 * 
 * 支持按日期范围查询 token 消耗，返回总量、按模型分组、按日期分组三个维度的数据。
 * 
 * 请求示例：GET /api/token-stats?startDate=2026-02-17&endDate=2026-03-19
 */
@RestController
@RequestMapping("/api/token-stats")
@CrossOrigin(origins = "${jclaw.gateway.cors-origin:*}", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.OPTIONS})
public class TokenStatsController {
    
    private static final JClawLogger logger = JClawLogger.getLogger("web");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    
    @Autowired
    private Config config;
    
    @Autowired
    private TokenUsageStore tokenUsageStore;
    
    /**
     * 查询 Token 消耗统计
     * 
     * 查询参数：startDate（yyyy-MM-dd）、endDate（yyyy-MM-dd），均可选，默认最近 30 天。
     * 
     * @param startDate 开始日期（可选）
     * @param endDate 结束日期（可选）
     * @return 统计结果，包含总量、按模型分组、按日期分组
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getTokenStats(
            @RequestParam(value = "startDate", required = false) String startDate,
            @RequestParam(value = "endDate", required = false) String endDate) {
        
        try {
            // 设置默认日期范围
            String actualEndDate = (endDate != null && !endDate.isBlank()) 
                    ? endDate 
                    : LocalDate.now().format(DATE_FORMATTER);
                    
            String actualStartDate = (startDate != null && !startDate.isBlank())
                    ? startDate
                    : LocalDate.now().minusDays(30).format(DATE_FORMATTER);
            
            TokenUsageStore.TokenStats stats = tokenUsageStore.query(actualStartDate, actualEndDate);
            
            Map<String, Object> result = buildResponse(stats, actualStartDate, actualEndDate);
            
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            logger.error("Token stats API error", Map.of("error", e.getMessage()));
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }
    
    // ==================== 私有辅助方法 ====================
    
    /**
     * 构建响应数据
     */
    private Map<String, Object> buildResponse(TokenUsageStore.TokenStats stats,
                                               String startDate, String endDate) {
        Map<String, Object> result = new HashMap<>();
        result.put("startDate", startDate);
        result.put("endDate", endDate);
        result.put("totalPromptTokens", stats.totalPromptTokens);
        result.put("totalCompletionTokens", stats.totalCompletionTokens);
        result.put("totalTokens", stats.totalPromptTokens + stats.totalCompletionTokens);
        result.put("totalCalls", stats.totalCalls);
        
        // 按模型分组
        List<Map<String, Object>> byModelArray = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : stats.byModel.entrySet()) {
            String[] parts = entry.getKey().split("::", 2);
            long[] values = entry.getValue();
            
            Map<String, Object> modelNode = new HashMap<>();
            modelNode.put("provider", parts.length > 0 ? parts[0] : "unknown");
            modelNode.put("model", parts.length > 1 ? parts[1] : "unknown");
            modelNode.put("promptTokens", values[0]);
            modelNode.put("completionTokens", values[1]);
            modelNode.put("totalTokens", values[0] + values[1]);
            modelNode.put("callCount", values[2]);
            byModelArray.add(modelNode);
        }
        result.put("byModel", byModelArray);
        
        // 按日期分组（按日期升序排列）
        List<Map<String, Object>> byDateArray = new ArrayList<>();
        stats.byDate.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    long[] values = entry.getValue();
                    Map<String, Object> dateNode = new HashMap<>();
                    dateNode.put("date", entry.getKey());
                    dateNode.put("promptTokens", values[0]);
                    dateNode.put("completionTokens", values[1]);
                    dateNode.put("totalTokens", values[0] + values[1]);
                    dateNode.put("callCount", values[2]);
                    byDateArray.add(dateNode);
                });
        result.put("byDate", byDateArray);
        
        return result;
    }
}