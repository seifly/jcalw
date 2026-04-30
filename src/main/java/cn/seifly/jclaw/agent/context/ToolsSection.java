package cn.seifly.jclaw.agent.context;

import cn.seifly.jclaw.tools.ToolRegistry;

/**
 * 工具部分。
 * 构建已注册工具的功能描述和使用方法。
 */
public class ToolsSection implements ContextSection {
    
    @Override
    public String name() {
        return "Tools";
    }
    
    @Override
    public String build(SectionContext context) {
        ToolRegistry tools = context.getTools();
        if (tools == null || tools.getSummaries().isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("## 可用工具\n\n");
        
        // 添加强制工具调用的说明 - 简化但更明确
        sb.append("### 🔧 工具调用规则（必须遵守）\n\n");
        
        sb.append("**核心规则**:\n");
        sb.append("- 当你需要执行任何操作时，必须使用工具调用（function calling）。\n");
        sb.append("- 不要用文字描述你会做什么，直接调用工具。\n");
        sb.append("- 不要使用伪代码或 Python 语法，使用标准的 function calling 格式。\n\n");
        
        sb.append("**什么情况下必须调用工具**:\n");
        sb.append("- 用户询问天气时 → 调用 `skills` 工具加载 weather 技能\n");
        sb.append("- 用户需要执行命令时 → 调用 `exec` 工具\n");
        sb.append("- 用户需要读取文件时 → 调用 `Read` 工具\n");
        sb.append("- 用户需要搜索代码时 → 调用 `search` 工具\n\n");
        
        sb.append("**禁止的行为**:\n");
        sb.append("- ❌ 不要使用 Python 语法：`print(skills(action='invoke', name='weather'))`\n");
        sb.append("- ❌ 不要用文字描述：`我需要调用 skills 工具来获取天气`\n");
        sb.append("- ❌ 不要假装执行：`我已经执行了命令...`\n\n");
        
        sb.append("**正确的做法**:\n");
        sb.append("- 直接通过 function calling 调用工具\n");
        sb.append("- 系统会通过 tools 参数告诉你有哪些工具可用以及如何调用它们\n");
        sb.append("- 当你调用工具时，系统会执行相应的操作并返回结果\n\n");
        
        sb.append("### 📋 你可以访问以下工具:\n\n");
        
        for (String summary : tools.getSummaries()) {
            sb.append(summary).append("\n");
        }
        
        sb.append("\n");
        
        sb.append("### ⚠️ 重要提示\n\n");
        sb.append("- 系统已经通过 `tools` 参数将工具定义发送给了你\n");
        sb.append("- 使用标准的 function calling 格式调用这些工具\n");
        sb.append("- 不要在回复中用文字描述工具调用，直接通过工具调用格式执行\n");
        sb.append("- 如果你需要调用某个工具，直接调用它，不要先告诉我你要调用\n");
        
        return sb.toString();
    }
}
