package cn.seifly.jclaw.providers;

import java.util.List;

/**
 * LLM response
 */
public class LLMResponse {
    
    private String content;
    private List<ToolCall> toolCalls;
    private String finishReason;
    private UsageInfo usage;
    
    public LLMResponse() {
    }
    
    public LLMResponse(String content) {
        this.content = content;
        this.finishReason = "stop";
    }
    
    public static LLMResponse text(String content) {
        return new LLMResponse(content);
    }
    
    // Getters and Setters
    public String getContent() {
        return content;
    }
    
    public void setContent(String content) {
        this.content = content;
    }
    
    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }
    
    public void setToolCalls(List<ToolCall> toolCalls) {
        this.toolCalls = toolCalls;
    }
    
    public String getFinishReason() {
        return finishReason;
    }
    
    public void setFinishReason(String finishReason) {
        this.finishReason = finishReason;
    }
    
    public UsageInfo getUsage() {
        return usage;
    }
    
    public void setUsage(UsageInfo usage) {
        this.usage = usage;
    }
    
    /**
     * 检查 if the response contains tool calls
     */
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
    
    /**
     * 将响应转换为详细的 JSON 字符串（用于日志打印）。
     * 
     * @return 包含所有关键信息的 JSON 字符串
     */
    public String toDetailedJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"content\":\"").append(escapeJson(content)).append("\",");
        sb.append("\"finishReason\":\"").append(finishReason).append("\",");
        sb.append("\"hasToolCalls\":").append(hasToolCalls()).append(",");
        
        if (hasToolCalls()) {
            sb.append("\"toolCalls\":[");
            for (int i = 0; i < toolCalls.size(); i++) {
                if (i > 0) sb.append(",");
                ToolCall tc = toolCalls.get(i);
                sb.append("{");
                sb.append("\"id\":\"").append(escapeJson(tc.getId())).append("\",");
                sb.append("\"name\":\"").append(escapeJson(tc.getName())).append("\",");
                sb.append("\"type\":\"").append(escapeJson(tc.getType())).append("\",");
                sb.append("\"arguments\":").append(tc.getArguments() != null ? tc.getArguments().toString() : "null");
                sb.append("}");
            }
            sb.append("],");
        }
        
        if (usage != null) {
            sb.append("\"usage\":{");
            sb.append("\"promptTokens\":").append(usage.getPromptTokens()).append(",");
            sb.append("\"completionTokens\":").append(usage.getCompletionTokens()).append(",");
            sb.append("\"totalTokens\":").append(usage.getTotalTokens());
            sb.append("},");
        }
        
        sb.append("\"contentLength\":").append(content != null ? content.length() : 0);
        sb.append("}");
        return sb.toString();
    }
    
    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }
    
    /**
     * Usage information
     */
    public static class UsageInfo {
        private int promptTokens;
        private int completionTokens;
        private int totalTokens;
        
        public UsageInfo() {
        }
        
        public UsageInfo(int promptTokens, int completionTokens, int totalTokens) {
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = totalTokens;
        }
        
        public int getPromptTokens() {
            return promptTokens;
        }
        
        public void setPromptTokens(int promptTokens) {
            this.promptTokens = promptTokens;
        }
        
        public int getCompletionTokens() {
            return completionTokens;
        }
        
        public void setCompletionTokens(int completionTokens) {
            this.completionTokens = completionTokens;
        }
        
        public int getTotalTokens() {
            return totalTokens;
        }
        
        public void setTotalTokens(int totalTokens) {
            this.totalTokens = totalTokens;
        }
    }
}
