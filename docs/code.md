用户消息: "北京天气怎么样?"
↓
[Agent 语义理解]
↓
[查看技能列表 - SkillsSection.buildSkillsSummary()]
↓
[匹配 weather 技能 - 根据 description 语义匹配]
↓
[调用 skills(action='invoke', name='weather')]
↓
[SkillsTool.executeInvoke() → SkillsLoader.loadSkill()]
↓
[返回技能完整内容 + base-path]
↓
[Agent 注入上下文，读取指令]
↓
[执行 curl -s "wttr.in/Beijing?format=3"]
↓
[返回结果给用户: "Beijing: ☀️ +22°C"]