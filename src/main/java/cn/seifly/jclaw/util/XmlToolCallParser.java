package cn.seifly.jclaw.util;

import cn.seifly.jclaw.providers.ToolCall;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class XmlToolCallParser {
    
    private static final ObjectMapper MAPPER = new ObjectMapper();
    
    private static final String TOOL_CALL_START = "<" + "tool_call>";
    private static final String TOOL_CALL_END = "</" + "tool_call>";
    private static final String FUNCTION_START = "<" + "function=";
    private static final String FUNCTION_END = "</" + "function>";
    private static final String PARAM_START = "<" + "parameter=";
    private static final String PARAM_END = "</" + "parameter>";
    
    private static final Pattern TOOL_CALL_BLOCK = Pattern.compile(
            Pattern.quote(TOOL_CALL_START) + "(.*?)" + Pattern.quote(TOOL_CALL_END),
            Pattern.DOTALL
    );
    
    // 匹配 JSON 格式的工具调用，如 {"name": "skills", "arguments": {...}}
    private static final Pattern JSON_TOOL_CALL_PATTERN = Pattern.compile(
            "\\{\\s*\"name\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"arguments\"\\s*:\\s*(\\{[^}]*\\}|\\[[^\\]]*\\]|\"[^\"]*\"|null|true|false|-?\\d+(?:\\.\\d+)?)\\s*\\}",
            Pattern.DOTALL
    );
    
    // 匹配包含在 markdown 代码块中的 JSON 工具调用
    private static final Pattern CODE_BLOCK_JSON_PATTERN = Pattern.compile(
            "```(?:json)?\\s*\\n?\\s*(\\{\\s*\"name\"\\s*:\\s*\"[^\"]+\"\\s*,\\s*\"arguments\"\\s*:.*?\\})\\s*\\n?```",
            Pattern.DOTALL
    );
    
    public static List<ToolCall> parseXmlToolCalls(String content) {
        List<ToolCall> toolCalls = new ArrayList<>();
        
        if (content == null || content.isEmpty()) {
            return toolCalls;
        }
        
        // 首先尝试解析 XML 格式的工具调用
        Matcher matcher = TOOL_CALL_BLOCK.matcher(content);
        while (matcher.find()) {
            String blockContent = matcher.group(1);
            ToolCall toolCall = parseFunctionBlock(blockContent);
            if (toolCall != null) {
                toolCalls.add(toolCall);
            }
        }
        
        // 如果没有找到 XML 格式的工具调用，尝试解析 JSON 格式
        if (toolCalls.isEmpty()) {
            // 先尝试解析代码块中的 JSON
            Matcher codeBlockMatcher = CODE_BLOCK_JSON_PATTERN.matcher(content);
            while (codeBlockMatcher.find()) {
                String jsonStr = codeBlockMatcher.group(1);
                ToolCall toolCall = parseJsonToolCall(jsonStr);
                if (toolCall != null) {
                    toolCalls.add(toolCall);
                }
            }
            
            // 如果代码块中也没有，尝试直接解析 JSON
            if (toolCalls.isEmpty()) {
                Matcher jsonMatcher = JSON_TOOL_CALL_PATTERN.matcher(content);
                while (jsonMatcher.find()) {
                    String name = jsonMatcher.group(1);
                    String argumentsStr = jsonMatcher.group(2);
                    
                    ToolCall toolCall = parseJsonToolCallWithParts(name, argumentsStr);
                    if (toolCall != null) {
                        toolCalls.add(toolCall);
                    }
                }
            }
        }
        
        return toolCalls;
    }
    
    public static List<ToolCall> extractXmlToolCalls(String content) {
        return parseXmlToolCalls(content);
    }
    
    /**
     * 解析 JSON 格式的工具调用
     * 格式: {"name": "tool_name", "arguments": {...}}
     */
    private static ToolCall parseJsonToolCall(String jsonStr) {
        try {
            Map<String, Object> map = MAPPER.readValue(jsonStr, new TypeReference<Map<String, Object>>() {});
            
            String name = (String) map.get("name");
            if (name == null || name.isEmpty()) {
                return null;
            }
            
            Object argumentsObj = map.get("arguments");
            Map<String, Object> arguments = new HashMap<>();
            
            if (argumentsObj instanceof Map) {
                arguments = (Map<String, Object>) argumentsObj;
            } else if (argumentsObj instanceof String) {
                // arguments 可能是 JSON 字符串
                try {
                    arguments = MAPPER.readValue((String) argumentsObj, new TypeReference<Map<String, Object>>() {});
                } catch (Exception e) {
                    // 如果无法解析为 Map，直接使用字符串
                    arguments.put("content", argumentsObj);
                }
            }
            
            ToolCall toolCall = new ToolCall();
            toolCall.setId(UUID.randomUUID().toString());
            toolCall.setType("function");
            toolCall.setName(name);
            toolCall.setArguments(arguments);
            
            return toolCall;
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * 解析 JSON 工具调用的各个部分
     */
    private static ToolCall parseJsonToolCallWithParts(String name, String argumentsStr) {
        try {
            Map<String, Object> arguments = new HashMap<>();
            
            if (argumentsStr != null && !argumentsStr.isEmpty()) {
                if (argumentsStr.startsWith("{")) {
                    // arguments 是对象
                    arguments = MAPPER.readValue(argumentsStr, new TypeReference<Map<String, Object>>() {});
                } else if (argumentsStr.startsWith("[")) {
                    // arguments 是数组
                    List<Object> list = MAPPER.readValue(argumentsStr, new TypeReference<List<Object>>() {});
                    arguments.put("items", list);
                } else if (argumentsStr.startsWith("\"")) {
                    // arguments 是字符串
                    String str = argumentsStr.substring(1, argumentsStr.length() - 1);
                    arguments.put("content", str);
                } else if ("null".equals(argumentsStr)) {
                    arguments = new HashMap<>();
                } else if ("true".equals(argumentsStr) || "false".equals(argumentsStr)) {
                    arguments.put("value", Boolean.parseBoolean(argumentsStr));
                } else {
                    // 可能是数字
                    try {
                        Number num = Double.parseDouble(argumentsStr);
                        arguments.put("value", num);
                    } catch (NumberFormatException e) {
                        arguments.put("content", argumentsStr);
                    }
                }
            }
            
            ToolCall toolCall = new ToolCall();
            toolCall.setId(UUID.randomUUID().toString());
            toolCall.setType("function");
            toolCall.setName(name);
            toolCall.setArguments(arguments);
            
            return toolCall;
        } catch (Exception e) {
            return null;
        }
    }
    
    private static ToolCall parseFunctionBlock(String blockContent) {
        if (blockContent == null || blockContent.isEmpty()) {
            return null;
        }
        
        int funcStartIdx = blockContent.indexOf(FUNCTION_START);
        if (funcStartIdx < 0) {
            return null;
        }
        
        int funcNameStart = funcStartIdx + FUNCTION_START.length();
        int funcNameEnd = blockContent.indexOf('>', funcNameStart);
        if (funcNameEnd < 0) {
            return null;
        }
        
        String functionName = blockContent.substring(funcNameStart, funcNameEnd).trim();
        
        int funcEndIdx = blockContent.indexOf(FUNCTION_END, funcNameEnd);
        if (funcEndIdx < 0) {
            funcEndIdx = blockContent.length();
        }
        
        String paramsContent = blockContent.substring(funcNameEnd + 1, funcEndIdx);
        
        Map<String, Object> arguments = parseParameters(paramsContent);
        
        ToolCall toolCall = new ToolCall();
        toolCall.setId(UUID.randomUUID().toString());
        toolCall.setType("function");
        toolCall.setName(functionName);
        toolCall.setArguments(arguments);
        
        return toolCall;
    }
    
    private static Map<String, Object> parseParameters(String paramsContent) {
        Map<String, Object> arguments = new HashMap<>();
        
        if (paramsContent == null || paramsContent.isEmpty()) {
            return arguments;
        }
        
        int idx = 0;
        while (idx < paramsContent.length()) {
            int paramStartIdx = paramsContent.indexOf(PARAM_START, idx);
            if (paramStartIdx < 0) {
                break;
            }
            
            int paramNameStart = paramStartIdx + PARAM_START.length();
            int paramNameEnd = paramsContent.indexOf('>', paramNameStart);
            if (paramNameEnd < 0) {
                break;
            }
            
            String paramName = paramsContent.substring(paramNameStart, paramNameEnd).trim();
            
            int paramValueStart = paramNameEnd + 1;
            int paramEndIdx = paramsContent.indexOf(PARAM_END, paramValueStart);
            if (paramEndIdx < 0) {
                paramEndIdx = paramsContent.length();
            }
            
            String paramValue = paramsContent.substring(paramValueStart, paramEndIdx).trim();
            
            arguments.put(paramName, paramValue);
            idx = paramEndIdx + PARAM_END.length();
        }
        
        return arguments;
    }
}
