package cn.seifly.jclaw.springai;

import cn.seifly.jclaw.tools.Tool;
import cn.seifly.jclaw.tools.ToolException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ToolAdapter {
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    public ToolCallback adapt(Tool tool) {
        return new SpringAiToolCallback(tool, objectMapper);
    }
    
    public List<ToolCallback> adaptAll(Iterable<Tool> tools) {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (Tool tool : tools) {
            callbacks.add(adapt(tool));
        }
        return callbacks;
    }
    
    public static class SpringAiToolCallback implements ToolCallback {
        
        private final Tool tool;
        private final ObjectMapper objectMapper;
        private final ToolDefinition toolDefinition;
        
        public SpringAiToolCallback(Tool tool, ObjectMapper objectMapper) {
            this.tool = tool;
            this.objectMapper = objectMapper;
            this.toolDefinition = createToolDefinition(tool);
        }
        
        @Override
        public ToolDefinition getToolDefinition() {
            return toolDefinition;
        }
        
        @Override
        public String call(String toolInput) {
            try {
                Map<String, Object> args = parseToolInput(toolInput);
                String result = tool.execute(args);
                return result;
            } catch (ToolException e) {
                throw new RuntimeException("Tool execution failed: " + e.getMessage(), e);
            } catch (Exception e) {
                throw new RuntimeException("Unexpected error: " + e.getMessage(), e);
            }
        }
        
        @SuppressWarnings("unchecked")
        private Map<String, Object> parseToolInput(String toolInput) {
            if (toolInput == null || toolInput.isEmpty()) {
                return new HashMap<>();
            }
            
            try {
                JsonNode root = objectMapper.readTree(toolInput);
                return objectMapper.convertValue(root, Map.class);
            } catch (Exception e) {
                return new HashMap<>();
            }
        }
        
        private static ToolDefinition createToolDefinition(Tool tool) {
            return ToolDefinition.builder()
                    .name(tool.name())
                    .description(tool.description())
                    .inputSchema(convertParameters(tool.parameters()))
                    .build();
        }
        
        @SuppressWarnings("unchecked")
        private static String convertParameters(Map<String, Object> originalParams) {
            if (originalParams == null) {
                return "{}";
            }
            
            try {
                ObjectMapper mapper = new ObjectMapper();
                return mapper.writeValueAsString(originalParams);
            } catch (Exception e) {
                return "{}";
            }
        }
    }
}
