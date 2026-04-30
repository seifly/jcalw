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

为什么 spring.ai.* 配置没有生效？
原因分析
您的项目有两套独立的配置系统：
Spring AI 自动配置（您配置的 spring.ai.openai.*）
这是 Spring AI starter 自带的自动配置机制
会自动创建 ChatModel Bean
jclaw 自定义配置系统（实际在使用的）
通过 JClawConfig.mergeFromApplicationYml() 读取 jclaw.* 配置
通过 SpringAiProviderFactory.initializeFromConfig() 从 config.getModels() 和 config.getProviders() 读取
完全不使用 spring.ai.* 配置
证据
从 SpringAiProviderFactory.java 第 39-76 行可以看到：