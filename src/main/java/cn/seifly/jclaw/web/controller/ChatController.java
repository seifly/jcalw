package cn.seifly.jclaw.web.controller;

import cn.seifly.jclaw.agent.AgentRuntime;
import cn.seifly.jclaw.config.Config;
import cn.seifly.jclaw.logger.JClawLogger;
import cn.seifly.jclaw.providers.LLMProvider;
import cn.seifly.jclaw.providers.StreamEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 聊天 API 控制器
 * 
 * 提供聊天、流式聊天、中断任务和状态查询功能。
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "${jclaw.gateway.cors-origin:*}", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS})
public class ChatController {
    
    private static final JClawLogger logger = JClawLogger.getLogger("web");
    private static final String DEFAULT_SESSION_ID = "web:default";
    
    private final ExecutorService executor = Executors.newCachedThreadPool();
    
    @Autowired
    private Config config;
    
    @Autowired
    private AgentRuntime agentRuntime;
    
    /**
     * 普通聊天请求
     * 
     * 解析 message/sessionId，同步调用 Agent 并返回完整响应。
     * 
     * @param request 包含 message 和 sessionId 的请求体
     * @return 聊天响应
     */
    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, String> request) {
        String message = request.get("message");
        String sessionId = request.getOrDefault("sessionId", DEFAULT_SESSION_ID);
        
        try {
            String response = agentRuntime.processDirect(message, sessionId);
            
            Map<String, Object> result = new HashMap<>();
            result.put("response", response);
            result.put("sessionId", sessionId);
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("Agent processing error", Map.of("error", e.getMessage()));
            
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("error", e.getMessage());
            
            return ResponseEntity.status(500).body(errorResult);
        }
    }
    
    /**
     * 流式聊天请求（SSE）
     * 
     * 设置 SSE 响应头并逐递将 Agent 输出推送到客户端。
     * 支持多模态内容，可以接收图片路径列表。
     * 
     * @param request 包含 message、sessionId 和 images 的请求体
     * @return SseEmitter 用于流式响应
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody Map<String, Object> request) {
        String message = (String) request.get("message");
        String sessionId = (String) request.getOrDefault("sessionId", DEFAULT_SESSION_ID);
        
        // 解析图片列表（多模态支持）
        List<String> images = parseImages(request);
        
        // 创建 SseEmitter，设置超时时间（5分钟）
        SseEmitter emitter = new SseEmitter(300000L);
        
        // 在单独的线程中执行流式处理
        executor.execute(() -> {
            try {
                streamAgentResponse(message, images, sessionId, emitter);
                writeSSEDone(emitter);
                emitter.complete();
            } catch (Exception e) {
                logger.error("Chat stream error", Map.of("error", e.getMessage()));
                try {
                    writeSSEError(emitter, e.getMessage());
                } catch (IOException ioException) {
                    logger.error("Failed to write error to SSE stream",
                            Map.of("error", ioException.getMessage()));
                }
                emitter.completeWithError(e);
            }
        });
        
        return emitter;
    }
    
    /**
     * 中断当前正在执行的 LLM 任务
     * 
     * @return 中断结果
     */
    @PostMapping("/chat/abort")
    public ResponseEntity<Map<String, Object>> abortChat() {
        try {
            boolean aborted = agentRuntime.abortCurrentTask();
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", aborted);
            result.put("message", aborted ? "Abort signal sent" : "No active task to abort");
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("Abort error", Map.of("error", e.getMessage()));
            
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("error", e.getMessage());
            
            return ResponseEntity.status(500).body(errorResult);
        }
    }
    
    /**
     * 查询当前是否有任务正在运行
     * 
     * @return 运行状态
     */
    @GetMapping("/chat/status")
    public ResponseEntity<Map<String, Object>> chatStatus() {
        Map<String, Object> result = new HashMap<>();
        result.put("running", agentRuntime.isTaskRunning());
        
        return ResponseEntity.ok(result);
    }
    
    // ==================== 私有辅助方法 ====================
    
    /**
     * 从请求中解析图片路径列表。
     * 支持 images 字段为字符串数组（图片路径）。
     */
    @SuppressWarnings("unchecked")
    private List<String> parseImages(Map<String, Object> request) {
        List<String> images = new ArrayList<>();
        Object imagesObj = request.get("images");
        
        if (imagesObj instanceof List<?>) {
            List<?> imagesList = (List<?>) imagesObj;
            for (Object imgObj : imagesList) {
                if (imgObj instanceof String) {
                    String imgPath = (String) imgObj;
                    if (imgPath != null && !imgPath.isEmpty()) {
                        images.add(imgPath);
                    }
                }
            }
        }
        
        if (!images.isEmpty()) {
            logger.info("收到图片请求", Map.of(
                    "image_count", images.size(),
                    "image_paths", images));
        }
        
        return images.isEmpty() ? null : images;
    }
    
    /**
     * 调用 AgentRuntime 流式接口，将每个事件序列化为 JSON 后写入 SSE 流。
     */
    private void streamAgentResponse(String message, List<String> images, String sessionId, SseEmitter emitter) {
        LLMProvider.EnhancedStreamCallback enhancedCallback = event -> {
            try {
                writeSSEJson(emitter, event);
            } catch (IOException e) {
                logger.error("SSE write error", Map.of("error", e.getMessage()));
            }
        };

        try {
            agentRuntime.processDirectStream(message, images, sessionId, enhancedCallback);
        } catch (Exception e) {
            logger.error("Agent stream processing error", Map.of("error", e.getMessage()));
            try {
                writeSSEJson(emitter, StreamEvent.content("错误: " + e.getMessage()));
            } catch (IOException ioException) {
                logger.error("Failed to write error to SSE stream",
                        Map.of("error", ioException.getMessage()));
            }
        }
    }
    
    /**
     * 将 StreamEvent 序列化为单行 JSON 后包装为 SSE data 事件并刷入输出流。
     */
    private void writeSSEJson(SseEmitter emitter, StreamEvent event) throws IOException {
        String json = event.toJson();
        // 确保 JSON 是单行（移除任何真实换行符，防止 SSE 协议解析错误）
        String singleLineJson = json.replace("\n", "\\n").replace("\r", "\\r");
        
        // 使用 SseEmitter 的 send 方法发送事件
        emitter.send(SseEmitter.event()
                .data(singleLineJson)
                .build());
    }
    
    /**
     * 向客户端发送 [DONE] 信号，标志流式输出结束。
     */
    private void writeSSEDone(SseEmitter emitter) throws IOException {
        emitter.send(SseEmitter.event()
                .data("[DONE]")
                .build());
    }
    
    /**
     * 向客户端发送错误事件，内容为错误信息的转义字符串。
     */
    private void writeSSEError(SseEmitter emitter, String errorMessage) throws IOException {
        emitter.send(SseEmitter.event()
                .data("[ERROR] " + escapeSSE(errorMessage))
                .build());
    }
    
    /**
     * 将内容中的换行符替换为 SSE 安全的占位符，防止协议解析错误。
     */
    private String escapeSSE(String content) {
        if (content == null) return "";
        return content.replace("\n", "\ndata: ");
    }
}