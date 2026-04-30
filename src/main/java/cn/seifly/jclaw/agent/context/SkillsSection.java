package cn.seifly.jclaw.agent.context;

import cn.seifly.jclaw.util.StringUtils;

import java.nio.file.Paths;

/**
 * 技能摘要部分。
 * 生成已安装技能的简要说明，并引导 AI 自主学习技能。
 */
public class SkillsSection implements ContextSection {
    
    @Override
    public String name() {
        return "Skills";
    }
    
    @Override
    public String build(SectionContext context) {
        String skillsSummary = context.getSkillsLoader().buildSkillsSummary();
        
        StringBuilder sb = new StringBuilder();
        sb.append("# Skills\n\n");
        
        if (StringUtils.isNotBlank(skillsSummary)) {
            appendInstalledSkillsSummary(sb, skillsSummary);
        }
        
        appendSkillSelfLearningGuide(sb, context.getWorkspace());
        
        return sb.toString();
    }
    
    private void appendInstalledSkillsSummary(StringBuilder sb, String skillsSummary) {
        sb.append("## 已安装技能\n\n");
        sb.append("以下技能扩展了你的能力。\n\n");
        sb.append(skillsSummary);
        sb.append("\n\n");
        
        // 添加强制技能调用的说明
        sb.append("## 🔧 技能触发和执行规则（必须遵守）\n\n");
        
        sb.append("### 什么时候调用技能？\n");
        sb.append("当满足以下任一条件时，必须主动调用 skills 工具加载对应技能：\n");
        sb.append("1. 用户的任务与某个技能的 description 高度匹配\n");
        sb.append("2. 用户明确提到某个技能名称\n");
        sb.append("3. 用户的请求需要技能中定义的专业知识或执行步骤\n\n");
        
        sb.append("### 如何调用技能？\n");
        sb.append("**重要**: 必须使用标准的 function calling 格式，不要使用伪代码或 Python 语法。\n\n");
        
        sb.append("#### ❌ 错误的调用方式:\n");
        sb.append("- `print(skills(action='invoke', name='weather'))` ❌\n");
        sb.append("- `我需要调用 skills 工具来获取天气技能` ❌\n");
        sb.append("- `skills(action='invoke', name='weather')` ❌ (这只是描述，不是工具调用)\n\n");
        
        sb.append("#### ✅ 正确的调用方式:\n");
        sb.append("使用标准的 function calling 格式：\n\n");
        
        sb.append("```json\n");
        sb.append("{\n");
        sb.append("  \"name\": \"skills\",\n");
        sb.append("  \"arguments\": {\n");
        sb.append("    \"action\": \"invoke\",\n");
        sb.append("    \"name\": \"weather\"\n");
        sb.append("  }\n");
        sb.append("}\n");
        sb.append("```\n\n");
        
        sb.append("### 调用技能后的执行步骤\n");
        sb.append("1. **调用 `skills(action='invoke', name='技能名')`** - 获取技能的完整内容和 base-path\n");
        sb.append("2. **阅读技能内容** - 理解技能中的详细指令和示例\n");
        sb.append("3. **提取用户参数** - 从用户的消息中提取必要的参数（如城市名、日期等）\n");
        sb.append("4. **执行具体操作** - 根据技能指令，调用相应的工具（如 exec、web_search 等）\n");
        sb.append("5. **返回结果** - 将执行结果整理后返回给用户\n\n");
        
        sb.append("### 示例：查询上海天气\n\n");
        sb.append("**用户输入**: \"上海天气怎么样？\"\n\n");
        
        sb.append("**步骤 1: 调用 weather 技能**\n");
        sb.append("```json\n");
        sb.append("{\n");
        sb.append("  \"name\": \"skills\",\n");
        sb.append("  \"arguments\": {\n");
        sb.append("    \"action\": \"invoke\",\n");
        sb.append("    \"name\": \"weather\"\n");
        sb.append("  }\n");
        sb.append("}\n");
        sb.append("```\n\n");
        
        sb.append("**步骤 2: 根据技能内容执行**\n");
        sb.append("技能内容会告诉你如何查询天气。例如：\n");
        sb.append("- 识别城市名：上海\n");
        sb.append("- 执行命令：`curl -s \"wttr.in/上海?format=%l:+%c+%t+%h+%w\"`\n\n");
        
        sb.append("**步骤 3: 调用 exec 工具执行命令**\n");
        sb.append("```json\n");
        sb.append("{\n");
        sb.append("  \"name\": \"exec\",\n");
        sb.append("  \"arguments\": {\n");
        sb.append("    \"command\": \"curl -s \\\"wttr.in/上海?format=%l:+%c+%t+%h+%w\\\"\"\n");
        sb.append("  }\n");
        sb.append("}\n");
        sb.append("```\n\n");
        
        sb.append("**步骤 4: 返回结果给用户**\n");
        sb.append("将 exec 的执行结果整理后返回给用户。\n\n");
        
        sb.append("### 重要提醒\n");
        sb.append("- **不要只调用技能就停止** - 调用 skills 只是获取指令，还需要根据指令执行具体操作\n");
        sb.append("- **不要省略步骤** - 必须先调用 skills 获取完整指令，再执行具体操作\n");
        sb.append("- **不要假装执行** - 必须实际调用工具，不要只是描述你会做什么\n\n");
    }

    private void appendSkillSelfLearningGuide(StringBuilder sb, String workspace) {
        String skillsPath = Paths.get(workspace).toAbsolutePath() + "/skills/";

        sb.append("""
                ## 技能自主学习

                使用 `skills` 工具自主管理技能：
                - `skills(action='list')` — 查看所有已安装技能
                - `skills(action='invoke', name='...')` — 调用技能，获取完整指令和 base-path
                - `skills(action='search', query='...')` — 从可信市场搜索技能
                - `skills(action='install', repo='owner/repo')` — 从 GitHub 安装技能
                - `skills(action='create', name='...', content='...')` — 创建新技能
                - `skills(action='edit', name='...', content='...')` — 改进现有技能
                - `skills(action='remove', name='...')` — 删除技能

                遇到无法处理的任务时：先 search → 找到则 install → 再 invoke；搜索不到再 create。

                技能包含可执行脚本时，invoke 返回的 base-path 可直接用于执行：
                `exec(command='python3 {base-path}/script.py')`

                """);

        sb.append("你创建的技能保存在 `").append(skillsPath).append("`，将在未来的对话中自动可用。\n");
    }
}
