package cn.seifly.jclaw.web.controller;

import cn.seifly.jclaw.agent.AgentRuntime;
import cn.seifly.jclaw.config.Config;
import cn.seifly.jclaw.logger.JClawLogger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 进化功能状态查询 API 控制器
 * 
 * 提供反馈功能和提示词优化功能的状态查询。
 */
@RestController
@RequestMapping("/api/feedback")
@CrossOrigin(origins = "${jclaw.gateway.cors-origin:*}", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.OPTIONS})
public class FeedbackController {
    
    private static final JClawLogger logger = JClawLogger.getLogger("web.feedback");
    
    @Autowired
    private Config config;
    
    @Autowired
    private AgentRuntime agentRuntime;
    
    /**
     * 获取进化功能状态
     * 
     * @return 包含 feedbackEnabled、promptOptimizationEnabled 和 optimizationStats 的状态信息
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getStatus() {
        boolean feedbackEnabled = agentRuntime.getFeedbackManager() != null;
        boolean promptOptEnabled = agentRuntime.getPromptOptimizer() != null;
        
        Map<String, Object> result = new HashMap<>();
        result.put("feedbackEnabled", feedbackEnabled);
        result.put("promptOptimizationEnabled", promptOptEnabled);
        
        if (feedbackEnabled && agentRuntime.getPromptOptimizer() != null) {
            Map<String, Object> stats = agentRuntime.getPromptOptimizer().getStats();
            result.put("optimizationStats", stats);
        }
        
        return ResponseEntity.ok(result);
    }
}