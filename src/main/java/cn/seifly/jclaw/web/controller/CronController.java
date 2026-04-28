package cn.seifly.jclaw.web.controller;

import cn.seifly.jclaw.config.Config;
import cn.seifly.jclaw.cron.CronJob;
import cn.seifly.jclaw.cron.CronSchedule;
import cn.seifly.jclaw.cron.CronService;
import cn.seifly.jclaw.logger.JClawLogger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 定时任务 API 控制器
 * 
 * 提供定时任务的增删改查和启停功能。
 * 
 * 注意：此 API 只有当 jclaw.gateway.enabled=true 时才可用。
 * 如果网关服务未启用，调用此 API 会返回 503 Service Unavailable 错误。
 */
@RestController
@RequestMapping("/api/cron")
@CrossOrigin(origins = "${jclaw.gateway.cors-origin:*}", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS})
public class CronController {
    
    private static final JClawLogger logger = JClawLogger.getLogger("web");
    
    /**
     * 当网关服务未启用时返回的错误信息
     */
    private static final Map<String, Object> SERVICE_UNAVAILABLE_ERROR = new HashMap<>();
    
    static {
        SERVICE_UNAVAILABLE_ERROR.put("error", "Cron service is not available");
        SERVICE_UNAVAILABLE_ERROR.put("message", "Please enable jclaw.gateway.enabled=true in your configuration");
    }
    
    @Autowired
    private Config config;
    
    /**
     * CronService 是可选的，只有当 jclaw.gateway.enabled=true 时才会被注入。
     */
    @Autowired(required = false)
    private CronService cronService;
    
    /**
     * 获取所有定时任务列表
     * 
     * @return 定时任务列表，包含 id、name、启用状态、计划表达式及下次运行时间
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listCronJobs() {
        if (cronService == null) {
            return ResponseEntity.status(503).build();
        }
        
        List<CronJob> jobs = cronService.listJobs(true);
        List<Map<String, Object>> result = new ArrayList<>();
        
        for (CronJob job : jobs) {
            Map<String, Object> jobNode = new HashMap<>();
            jobNode.put("id", job.getId());
            jobNode.put("name", job.getName());
            jobNode.put("enabled", job.isEnabled());
            jobNode.put("message", job.getPayload().getMessage());
            
            if (job.getSchedule().getKind() == CronSchedule.ScheduleKind.CRON) {
                jobNode.put("schedule", job.getSchedule().getExpr());
            } else if (job.getSchedule().getKind() == CronSchedule.ScheduleKind.EVERY) {
                jobNode.put("schedule", "every " + (job.getSchedule().getEveryMs() / 1000) + "s");
            }
            
            if (job.getState().getNextRunAtMs() != null) {
                jobNode.put("nextRun", job.getState().getNextRunAtMs());
            }
            
            result.add(jobNode);
        }
        
        return ResponseEntity.ok(result);
    }
    
    /**
     * 创建新定时任务
     * 
     * 支持 cron 表达式与固定间隔两种方式。
     * 缺少 schedule 字段时返回 400。
     * 
     * @param request 包含 name、message、cron 或 everySeconds、channel、to 的请求体
     * @return 创建结果，包含任务 id
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createCronJob(@RequestBody Map<String, Object> request) {
        if (cronService == null) {
            return ResponseEntity.status(503).body(SERVICE_UNAVAILABLE_ERROR);
        }
        
        String name = (String) request.getOrDefault("name", "");
        String message = (String) request.getOrDefault("message", "");
        
        CronSchedule schedule;
        if (request.containsKey("cron")) {
            schedule = CronSchedule.cron((String) request.get("cron"));
        } else if (request.containsKey("everySeconds")) {
            schedule = CronSchedule.every(((Number) request.get("everySeconds")).longValue() * 1000);
        } else {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Missing schedule");
            return ResponseEntity.status(400).body(error);
        }
        
        String channel = request.containsKey("channel") ? (String) request.get("channel") : null;
        String to = request.containsKey("to") ? (String) request.get("to") : null;
        
        CronJob job = cronService.addJob(name, schedule, message, channel, to);
        
        Map<String, Object> result = new HashMap<>();
        result.put("id", job.getId());
        
        return ResponseEntity.ok(result);
    }
    
    /**
     * 删除指定定时任务
     * 
     * @param id 任务 id
     * @return 删除结果
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteCronJob(@PathVariable("id") String id) {
        if (cronService == null) {
            return ResponseEntity.status(503).body(SERVICE_UNAVAILABLE_ERROR);
        }
        
        boolean removed = cronService.removeJob(id);
        
        if (removed) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "Job removed");
            return ResponseEntity.ok(result);
        } else {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Job not found");
            return ResponseEntity.status(404).body(error);
        }
    }
    
    /**
     * 启用或禁用指定定时任务
     * 
     * @param id 任务 id
     * @param request 包含 enabled 字段的请求体
     * @return 更新结果
     */
    @PutMapping("/{id}/enable")
    public ResponseEntity<Map<String, Object>> enableCronJob(
            @PathVariable("id") String id,
            @RequestBody Map<String, Object> request) {
        
        if (cronService == null) {
            return ResponseEntity.status(503).body(SERVICE_UNAVAILABLE_ERROR);
        }
        
        boolean enabled = (Boolean) request.getOrDefault("enabled", true);
        CronJob job = cronService.enableJob(id, enabled);
        
        if (job != null) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "Job " + (enabled ? "enabled" : "disabled"));
            return ResponseEntity.ok(result);
        } else {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Job not found");
            return ResponseEntity.status(404).body(error);
        }
    }
}