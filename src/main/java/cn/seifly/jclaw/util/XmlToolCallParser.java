package cn.seifly.jclaw.util;

import cn.seifly.jclaw.providers.ToolCall;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class XmlToolCallParser {
    
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
    
    public static List<ToolCall> parseXmlToolCalls(String content) {
        List<ToolCall> toolCalls = new ArrayList<>();
        
        if (content == null || content.isEmpty()) {
            return toolCalls;
        }
        
        Matcher matcher = TOOL_CALL_BLOCK.matcher(content);
        while (matcher.find()) {
            String blockContent = matcher.group(1);
            ToolCall toolCall = parseFunctionBlock(blockContent);
            if (toolCall != null) {
                toolCalls.add(toolCall);
            }
        }
        
        return toolCalls;
    }
    
    public static List<ToolCall> extractXmlToolCalls(String content) {
        return parseXmlToolCalls(content);
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
