package cn.seifly.jclaw.config;

import cn.seifly.jclaw.agent.AgentRuntime;
import cn.seifly.jclaw.bus.MessageBus;
import cn.seifly.jclaw.channels.ChannelManager;
import cn.seifly.jclaw.cron.CronService;
import cn.seifly.jclaw.logger.JClawLogger;
import cn.seifly.jclaw.providers.LLMProvider;
import cn.seifly.jclaw.security.SecurityGuard;
import cn.seifly.jclaw.session.SessionManager;
import cn.seifly.jclaw.skills.SkillsLoader;
import cn.seifly.jclaw.springai.SpringAiProvider;
import cn.seifly.jclaw.springai.SpringAiModelManager;
import cn.seifly.jclaw.springai.SpringAiProviderFactory;
import cn.seifly.jclaw.springai.ToolAdapter;
import cn.seifly.jclaw.tools.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * jclaw Spring Boot 配置类
 * 
 * 这个类负责将 jclaw 的核心组件集成到 Spring Boot 应用中。
 * 它提供了配置加载、Bean 管理和服务生命周期管理。
 * 
 * 配置优先级：
 * 1. application.yml 中的 jclaw.* 配置（最高优先级）
 * 2. 默认配置（最低优先级）
 */
@Configuration
@EnableConfigurationProperties(JClawProperties.class)
public class JClawConfig implements EnvironmentAware, WebMvcConfigurer {
    
    private static final JClawLogger logger = JClawLogger.getLogger("config");
    
    private Environment environment;
    
    private Config config;
    private MessageBus messageBus;
    private AgentRuntime agentRuntime;
    private ChannelManager channelManager;
    private CronService cronService;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    
    @Autowired
    private SpringAiModelManager springAiModelManager;
    
    @Autowired
    private ToolAdapter toolAdapter;
    
    @Autowired(required = false)
    private SpringAiProviderFactory springAiProviderFactory;
    
    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }
    
    /**
     * 初始化配置
     * 
     * 配置优先级：
     * 1. application.yml 中的 jclaw.* 配置（最高优先级）
     * 2. .env 文件
     * 3. ~/.jclaw/config.json 配置
     * 4. 默认配置（最低优先级）
     */
    @PostConstruct
    public void init(){
        try {
            //config = ConfigLoader.load();
            config = Config.defaultConfig();
            logger.info("Loaded configuration from config.json", Map.of(
                    "workspace", config.getWorkspacePath(),
                    "model", config.getAgent().getModel()
            ));
        } catch (Exception e) {
            logger.info("No config.json found, using defaults", Map.of("error", e.getMessage()));
            config = Config.defaultConfig();
        }
        
        if (environment != null) {
            // 打印所有读取到的配置（用于调试）
            logConfigDebugInfo();
            
            mergeFromApplicationYml(config);
        }
        
        logger.info("jclaw configuration initialized", Map.of(
            "workspace", config.getAgent().getWorkspace(),
            "model", config.getAgent().getModel(),
            "provider", config.getAgent().getProvider() != null ? config.getAgent().getProvider() : "null",
            "dashscopeApiKey", config.getProviders().getDashscope() != null && config.getProviders().getDashscope().getApiKey() != null ? 
                "set (" + config.getProviders().getDashscope().getApiKey().length() + " chars)" : "not set",
            "feishuEnabled", config.getChannels().getFeishu().isEnabled(),
            "wechatEnabled", config.getChannels().getWechat().isEnabled()
        ));
        
        initializeSpringAiModels();
    }
    
    private void initializeSpringAiModels() {
        if (springAiProviderFactory != null) {
            try {
                springAiProviderFactory.initializeFromConfig(config);
                logger.info("Spring AI models initialized from config");
            } catch (Exception e) {
                logger.warn("Failed to initialize Spring AI models", Map.of(
                    "error", e.getMessage()
                ));
            }
        } else {
            logger.warn("SpringAiProviderFactory not available, falling back to HTTPProvider");
        }
    }
    
    /**
     * 打印配置调试信息
     */
    private void logConfigDebugInfo() {
        String dashscopeApiKey = environment.getProperty("jclaw.providers.dashscope.api-key");
        String agentModel = environment.getProperty("jclaw.agent.model");
        String agentProvider = environment.getProperty("jclaw.agent.provider");
        String feishuEnabled = environment.getProperty("jclaw.channels.feishu.enabled");
        String feishuAppId = environment.getProperty("jclaw.channels.feishu.app-id");
        
        logger.info("Reading config from application.yml", Map.of(
            "agent.model", agentModel != null ? agentModel : "null",
            "agent.provider", agentProvider != null ? agentProvider : "null",
            "dashscope.api-key", dashscopeApiKey != null ? "set (" + dashscopeApiKey.length() + " chars)" : "null",
            "feishu.enabled", feishuEnabled != null ? feishuEnabled : "null",
            "feishu.app-id", feishuAppId != null ? feishuAppId : "null"
        ));
    }
    
    /**
     * 启动 AgentRuntime 的消息循环和 ChannelManager
     * 
     * 这个方法在 Spring 上下文刷新后调用（所有 Bean 创建完成后），
     * 在独立线程中启动 AgentRuntime.run() 来消费 MessageBus 中的消息。
     * 
     * 这对于处理来自通道（如飞书、Telegram 等）的消息是必需的。
     */
    @EventListener(ContextRefreshedEvent.class)
    public void startServices() {
        if (agentRuntime != null) {
            logger.info("Starting AgentRuntime message loop");
            executorService.execute(() -> {
                try {
                    agentRuntime.run();
                } catch (Exception e) {
                    logger.error("AgentRuntime 消息循环错误", Map.of(
                            "error_type", e.getClass().getSimpleName(),
                            "error_message", e.getMessage()
                    ), e);
                }
            });
        }
        
        if (channelManager != null) {
            logger.info("Starting channel manager");
            channelManager.startAll();
        }
    }
    
    /**
     * 从 application.yml 中读取配置并合并到 Config 对象
     * 
     * 配置优先级：
     * 1. application.yml 中的 jclaw.* 配置（最高优先级）
     * 2. 默认配置（最低优先级）
     * 
     * @param config 从 config.json 加载的配置或默认配置
     */
    private void mergeFromApplicationYml(Config config) {
        mergeAgentConfig(config.getAgent());
        mergeGatewayConfig(config.getGateway());
        mergeProvidersConfig(config.getProviders());
        mergeChannelsConfig(config.getChannels());
    }
    
    /**
     * 合并通道配置
     */
    private void mergeChannelsConfig(ChannelsConfig channels) {
        // 飞书配置
        mergeFeishuConfig(channels.getFeishu());
        // Telegram 配置
        mergeTelegramConfig(channels.getTelegram());
        // Discord 配置
        mergeDiscordConfig(channels.getDiscord());
        // 微信配置
        mergeWechatConfig(channels.getWechat());
        // 其他通道...
    }

    /**
     * 合并微信配置
     */
    private void mergeWechatConfig(ChannelsConfig.WechatConfig wechat) {
        Boolean enabled = getProperty("jclaw.channels.wechat.enabled", Boolean.class);
        if (enabled != null) {
            wechat.setEnabled(enabled);
        }

        Integer pollIntervalMs = getProperty("jclaw.channels.wechat.poll-interval-ms", Integer.class);
        if (pollIntervalMs != null) {
            wechat.setPollIntervalMs(pollIntervalMs);
        }

        Integer loginTimeoutSeconds = getProperty("jclaw.channels.wechat.login-timeout-seconds", Integer.class);
        if (loginTimeoutSeconds != null) {
            wechat.setLoginTimeoutSeconds(loginTimeoutSeconds);
        }
    }
    
    /**
     * 合并飞书配置
     */
    private void mergeFeishuConfig(ChannelsConfig.FeishuConfig feishu) {
        Boolean enabled = getProperty("jclaw.channels.feishu.enabled", Boolean.class);
        if (enabled != null) {
            feishu.setEnabled(enabled);
        }
        
        String appId = getProperty("jclaw.channels.feishu.app-id");
        if (appId != null) {
            feishu.setAppId(appId);
        }
        
        String appSecret = getProperty("jclaw.channels.feishu.app-secret");
        if (appSecret != null) {
            feishu.setAppSecret(appSecret);
        }
        
        String encryptKey = getProperty("jclaw.channels.feishu.encrypt-key");
        if (encryptKey != null) {
            feishu.setEncryptKey(encryptKey);
        }
        
        String verificationToken = getProperty("jclaw.channels.feishu.verification-token");
        if (verificationToken != null) {
            feishu.setVerificationToken(verificationToken);
        }
        
        String connectionMode = getProperty("jclaw.channels.feishu.connection-mode");
        if (connectionMode != null) {
            feishu.setConnectionMode(connectionMode);
        }
    }
    
    /**
     * 合并 Telegram 配置
     */
    private void mergeTelegramConfig(ChannelsConfig.TelegramConfig telegram) {
        Boolean enabled = getProperty("jclaw.channels.telegram.enabled", Boolean.class);
        if (enabled != null) {
            telegram.setEnabled(enabled);
        }
        
        String token = getProperty("jclaw.channels.telegram.token");
        if (token != null) {
            telegram.setToken(token);
        }
    }
    
    /**
     * 合并 Discord 配置
     */
    private void mergeDiscordConfig(ChannelsConfig.DiscordConfig discord) {
        Boolean enabled = getProperty("jclaw.channels.discord.enabled", Boolean.class);
        if (enabled != null) {
            discord.setEnabled(enabled);
        }
        
        String token = getProperty("jclaw.channels.discord.token");
        if (token != null) {
            discord.setToken(token);
        }
    }
    
    /**
     * 合并 Agent 配置
     */
    private void mergeAgentConfig(AgentConfig agent) {
        String workspace = getProperty("jclaw.agent.workspace");
        if (workspace != null) {
            agent.setWorkspace(workspace);
        }
        
        String model = getProperty("jclaw.agent.model");
        if (model != null) {
            agent.setModel(model);
        }
        
        String provider = getProperty("jclaw.agent.provider");
        if (provider != null) {
            agent.setProvider(provider);
        }
        
        Integer maxTokens = getProperty("jclaw.agent.max-tokens", Integer.class);
        if (maxTokens != null) {
            agent.setMaxTokens(maxTokens);
        }
        
        Double temperature = getProperty("jclaw.agent.temperature", Double.class);
        if (temperature != null) {
            agent.setTemperature(temperature);
        }
        
        Integer maxToolIterations = getProperty("jclaw.agent.max-tool-iterations", Integer.class);
        if (maxToolIterations != null) {
            agent.setMaxToolIterations(maxToolIterations);
        }
        
        Boolean heartbeatEnabled = getProperty("jclaw.agent.heartbeat-enabled", Boolean.class);
        if (heartbeatEnabled != null) {
            agent.setHeartbeatEnabled(heartbeatEnabled);
        }
        
        Boolean restrictToWorkspace = getProperty("jclaw.agent.restrict-to-workspace", Boolean.class);
        if (restrictToWorkspace != null) {
            agent.setRestrictToWorkspace(restrictToWorkspace);
        }
    }
    
    /**
     * 合并 Gateway 配置
     */
    private void mergeGatewayConfig(GatewayConfig gateway) {
        String host = getProperty("jclaw.gateway.host");
        if (host != null) {
            gateway.setHost(host);
        }
        
        Integer port = getProperty("jclaw.gateway.port", Integer.class);
        if (port != null) {
            gateway.setPort(port);
        }
        
        String username = getProperty("jclaw.gateway.username");
        if (username != null) {
            gateway.setUsername(username);
        }
        
        String password = getProperty("jclaw.gateway.password");
        if (password != null) {
            gateway.setPassword(password);
        }
        
        String corsOrigin = getProperty("jclaw.gateway.cors-origin");
        if (corsOrigin != null) {
            gateway.setCorsOrigin(corsOrigin);
        }
        
        Integer rateLimitPerMinute = getProperty("jclaw.gateway.rate-limit-per-minute", Integer.class);
        if (rateLimitPerMinute != null) {
            gateway.setRateLimitPerMinute(rateLimitPerMinute);
        }
    }
    
    /**
     * 合并 Providers 配置
     */
    private void mergeProvidersConfig(ProvidersConfig providers) {
        mergeProviderConfig(providers.getOpenrouter(), "jclaw.providers.openrouter");
        mergeProviderConfig(providers.getAnthropic(), "jclaw.providers.anthropic");
        mergeProviderConfig(providers.getOpenai(), "jclaw.providers.openai");
        mergeProviderConfig(providers.getZhipu(), "jclaw.providers.zhipu");
        mergeProviderConfig(providers.getGemini(), "jclaw.providers.gemini");
        mergeProviderConfig(providers.getDashscope(), "jclaw.providers.dashscope");
        mergeProviderConfig(providers.getOllama(), "jclaw.providers.ollama");
    }
    
    /**
     * 合并单个 Provider 配置
     */
    private void mergeProviderConfig(ProvidersConfig.ProviderConfig provider, String prefix) {
        String apiKey = getProperty(prefix + ".api-key");
        if (apiKey != null) {
            provider.setApiKey(apiKey);
        }
        
        String apiBase = getProperty(prefix + ".api-base");
        if (apiBase != null) {
            provider.setApiBase(apiBase);
        }
    }
    
    /**
     * 获取字符串属性
     */
    private String getProperty(String key) {
        return environment != null ? environment.getProperty(key) : null;
    }
    
    /**
     * 获取指定类型的属性
     */
    private <T> T getProperty(String key, Class<T> targetType) {
        return environment != null ? environment.getProperty(key, targetType) : null;
    }
    
    /**
     * 提供 Config Bean
     * 
     * @return 配置对象
     */
    @Bean
    public Config config() {
        return config;
    }
    
    /**
     * 提供 MessageBus Bean
     * 
     * @return 消息总线实例
     */
    @Bean
    public MessageBus messageBus() {
        if (messageBus == null) {
            messageBus = new MessageBus();
        }
        return messageBus;
    }
    
    /**
     * 提供 ChannelManager Bean
     * 
     * 负责管理所有消息通道（飞书、Telegram、Discord 等）。
     * 还会将 ChannelManager 注入到 AgentRuntime 中。
     * 
     * @param config 配置对象
     * @param messageBus 消息总线实例
     * @param agentRuntime AgentRuntime 实例
     * @return ChannelManager 实例
     */
    @Bean
    public ChannelManager channelManager(Config config, MessageBus messageBus, AgentRuntime agentRuntime) {
        if (channelManager == null) {
            channelManager = new ChannelManager(config, messageBus);
            agentRuntime.setChannelManager(channelManager);
            logger.info("ChannelManager created and injected into AgentRuntime");
        }
        return channelManager;
    }
    
    /**
     * 提供 AgentRuntime Bean
     * 
     * 注意：此 Bean 可能需要 LLMProvider 才能完全初始化。
     * 如果没有配置 API Key，provider 可能为 null。
     * 
     * @return AgentRuntime 实例
     */
    @Bean
    public AgentRuntime agentRuntime(Config config, MessageBus messageBus) {
        if (agentRuntime == null) {
            LLMProvider provider = null;
            try {
                if (config.validate().isEmpty()) {
                    provider = createProvider(config);
                }
            } catch (Exception e) {
                logger.error("LLM Provider 配置失败", e);
                logger.warn("LLM Provider 配置失败", Map.of(
                        "error_type", e.getClass().getSimpleName(),
                        "error_message", e.getMessage()
                ));
            }
            agentRuntime = new AgentRuntime(config, messageBus, provider);
            
            // 创建 SecurityGuard 用于安全检查
            String workspace = config.getWorkspacePath();
            boolean restrictToWorkspace = config.getAgent().isRestrictToWorkspace();
            SecurityGuard securityGuard = new SecurityGuard(workspace, restrictToWorkspace);
            logger.info("SecurityGuard enabled", Map.of(
                "workspace", workspace,
                "restrictToWorkspace", restrictToWorkspace
            ));
            
            // 注册核心工具（带 SecurityGuard）
            agentRuntime.registerTool(new ExecTool(workspace, securityGuard));
            agentRuntime.registerTool(new ListDirTool(securityGuard));
            agentRuntime.registerTool(new ReadFileTool(securityGuard));
            agentRuntime.registerTool(new WriteFileTool(securityGuard));
            agentRuntime.registerTool(new EditFileTool(securityGuard));
            agentRuntime.registerTool(new WebSearchTool(config.getTools().getWeb().getSearch().getApiKey(), config.getTools().getWeb().getSearch().getMaxResults()));
            agentRuntime.registerTool(new WebFetchTool(50000));
            agentRuntime.registerTool(new SkillsTool(workspace, agentRuntime.getSkillsLoader()));
            agentRuntime.registerTool(new MessageTool());
            
            logger.info("Registered core tools", Map.of(
                "count", agentRuntime.getToolRegistry().count(),
                "tools", agentRuntime.getToolRegistry().list()
            ));
        }
        return agentRuntime;
    }
    
    /**
     * 提供 SessionManager Bean
     * 
     * 从 AgentRuntime 中获取 SessionManager 实例，确保是同一个实例，
     * 避免内存状态不一致的问题。
     * 
     * @param agentRuntime AgentRuntime 实例
     * @return SessionManager 实例
     */
    @Bean
    public SessionManager sessionManager(AgentRuntime agentRuntime) {
        return agentRuntime.getSessionManager();
    }
    
    /**
     * 提供 CronService Bean
     * 
     * 创建定时任务服务实例，负责管理定时任务的调度和执行。
     * 使用工作空间路径下的 cron/jobs.json 作为持久化存储。
     * 
     * @param config 配置对象
     * @param agentRuntime AgentRuntime 实例（用于任务执行回调）
     * @param messageBus 消息总线实例
     * @return CronService 实例
     */
    @Bean
    public CronService cronService(Config config, AgentRuntime agentRuntime, MessageBus messageBus) {
        String workspacePath = config.getWorkspacePath();
        String cronStorePath = Paths.get(workspacePath, "cron", "jobs.json").toString();
        
        CronService.JobHandler jobHandler = job -> {
            String channel = job.getPayload().getChannel();
            String to = job.getPayload().getTo();
            String message = job.getPayload().getMessage();
            
            if (channel == null || channel.isEmpty()) {
                channel = "cli";
            }
            if (to == null || to.isEmpty()) {
                to = "direct";
            }
            
            String sessionKey = "cron-" + job.getId();
            agentRuntime.processDirectWithChannel(message, sessionKey, channel, to);
            
            logger.info("Cron job executed", Map.of(
                    "job_id", job.getId(),
                    "job_name", job.getName(),
                    "channel", channel,
                    "to", to
            ));
            
            return "ok";
        };
        
        this.cronService = new CronService(cronStorePath, jobHandler);
        
        logger.info("CronService created", Map.of(
                "store_path", cronStorePath
        ));
        
        CronTool.JobExecutor cronToolJobExecutor = (content, sessionKey, channel, chatId) -> {
            agentRuntime.processDirectWithChannel(content, sessionKey, channel, chatId);
            return "ok";
        };
        
        CronTool cronTool = new CronTool(this.cronService, cronToolJobExecutor, messageBus);
        agentRuntime.registerTool(cronTool);
        
        logger.info("CronTool registered with AgentRuntime", Map.of(
                "tool_name", cronTool.name()
        ));
        
        this.cronService.start();
        
        logger.info("CronService started", Map.of(
                "scheduler", "quartz"
        ));
        
        return this.cronService;
    }
    
    /**
     * 提供 SkillsLoader Bean
     * 
     * 从 AgentRuntime 中获取 SkillsLoader 实例，确保是同一个实例，
     * 保持技能列表一致性。
     * 
     * @param agentRuntime AgentRuntime 实例
     * @return SkillsLoader 实例
     */
    @Bean
    public SkillsLoader skillsLoader(AgentRuntime agentRuntime) {
        return agentRuntime.getSkillsLoader();
    }
    
    /**
     * 提供 TokenUsageStore Bean
     * 
     * 从 AgentRuntime 中获取 TokenUsageStore 实例，确保是同一个实例。
     * 如果 AgentRuntime 中没有 TokenUsageStore（因为没有配置 LLM Provider），
     * 则创建一个新的 TokenUsageStore 实例。
     * 
     * @param agentRuntime AgentRuntime 实例
     * @param config 配置对象
     * @return TokenUsageStore 实例
     */
    @Bean
    public TokenUsageStore tokenUsageStore(AgentRuntime agentRuntime, Config config) {
        TokenUsageStore store = agentRuntime.getTokenUsageStore();
        if (store != null) {
            return store;
        }
        logger.info("Creating default TokenUsageStore (no LLM Provider configured)");
        return new TokenUsageStore(config.getWorkspacePath());
    }
    
    /**
     * 清理资源
     * 
     * 在应用关闭时优雅地停止所有服务。
     */
    @PreDestroy
    public void cleanup() {
        logger.info("Stopping jclaw services...");
        
        if (channelManager != null) {
            try {
                channelManager.stopAll();
                logger.info("Channel manager stopped");
            } catch (Exception e) {
                logger.error("停止 Channel Manager 错误", Map.of(
                        "error_type", e.getClass().getSimpleName(),
                        "error_message", e.getMessage()
                ), e);
            }
        }
        
        if (agentRuntime != null) {
            try {
                agentRuntime.stop();
                logger.info("AgentRuntime stopped");
            } catch (Exception e) {
                logger.error("停止 AgentRuntime 错误", Map.of(
                        "error_type", e.getClass().getSimpleName(),
                        "error_message", e.getMessage()
                ), e);
            }
        }
        
        if (cronService != null && cronService.isRunning()) {
            try {
                cronService.stop();
                logger.info("CronService stopped");
            } catch (Exception e) {
                logger.error("停止 CronService 错误", Map.of(
                        "error_type", e.getClass().getSimpleName(),
                        "error_message", e.getMessage()
                ), e);
            }
        }
        
        if (!executorService.isShutdown()) {
            executorService.shutdown();
            logger.info("Executor service stopped");
        }
        
        if (messageBus != null && !messageBus.isClosed()) {
            messageBus.close();
            logger.info("MessageBus closed");
        }
        
        logger.info("jclaw services stopped");
    }
    
    /**
     * 创建 LLM Provider
     * 
     * 优先使用 Spring AI 实现，如果不可用则回退到 HTTPProvider。
     * 
     * @param config 配置对象
     * @return LLMProvider 实例
     */
    private LLMProvider createProvider(Config config) {
        if (springAiProviderFactory != null) {
            String defaultModel = config.getAgent().getModel();
            if (springAiProviderFactory.hasModel(defaultModel)) {
                try {
                    SpringAiProvider provider = springAiProviderFactory.createDefaultProvider();
                    logger.info("Created Spring AI provider", Map.of(
                            "model", defaultModel,
                            "provider", provider.getName()
                    ));
                    return provider;
                } catch (Exception e) {
                    logger.warn("Failed to create Spring AI provider, falling back to HTTPProvider", Map.of(
                            "error", e.getMessage()
                    ));
                }
            } else {
                logger.warn("Model not registered in Spring AI, falling back to HTTPProvider", Map.of(
                        "model", defaultModel
                ));
            }
        }
        
        return config.getProviders().getFirstValidProvider()
                .map(providerConfig -> {
                    String providerName = config.getProviders().getProviderName(providerConfig);
                    String apiBase = providerConfig.getApiBase();
                    if (apiBase == null || apiBase.isEmpty()) {
                        apiBase = ProvidersConfig.getDefaultApiBase(providerName);
                    }
                    return new cn.seifly.jclaw.providers.HTTPProvider(
                            providerConfig.getApiKey(), 
                            apiBase
                    );
                })
                .orElse(null);
    }
    
    /**
     * 配置静态资源映射
     * 
     * 将 /css/**、/js/**、/index.html 等 URL 路径映射到 classpath:/web/ 目录下的资源。
     * 这样前端资源就可以被正确访问了。
     * 
     * 注意：我们不使用 /** 映射，以避免与 API 路径冲突。
     * 首页 / 通过 addViewControllers 映射到 index.html。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/css/**")
                .addResourceLocations("classpath:/web/css/");
        
        registry.addResourceHandler("/js/**")
                .addResourceLocations("classpath:/web/js/");
        
        registry.addResourceHandler("/images/**")
                .addResourceLocations("classpath:/web/images/");
        
        registry.addResourceHandler("/index.html", "/sw.js", "/favicon.ico")
                .addResourceLocations("classpath:/web/");
        
        logger.info("Static resource handlers configured", Map.of(
            "css", "classpath:/web/css/",
            "js", "classpath:/web/js/",
            "images", "classpath:/web/images/",
            "web", "classpath:/web/"
        ));
    }
    
    /**
     * 配置视图控制器
     * 
     * 将根路径 / 映射到 index.html。
     */
    @Override
    public void addViewControllers(org.springframework.web.servlet.config.annotation.ViewControllerRegistry registry) {
        registry.addRedirectViewController("/", "/index.html");
        
        logger.info("View controller configured: / -> redirect:/index.html");
    }
}
