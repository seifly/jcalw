package cn.seifly.jclaw.config;

import cn.seifly.jclaw.evolution.EvolutionConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * jclaw 配置属性类
 * 
 * 使用 Spring Boot 的 @ConfigurationProperties 方式从 application.yml 加载配置。
 * 
 * 配置优先级：
 * 1. application.yml 中的 jclaw.* 配置（最高优先级）
 * 2. ~/.jclaw/config.json 配置
 * 3. 默认配置（最低优先级）
 */
@ConfigurationProperties(prefix = "jclaw")
public class JClawProperties {

    @NestedConfigurationProperty
    private ModelsProperties models = new ModelsProperties();

    @NestedConfigurationProperty
    private AgentProperties agent = new AgentProperties();

    @NestedConfigurationProperty
    private ChannelsProperties channels = new ChannelsProperties();

    @NestedConfigurationProperty
    private ProvidersProperties providers = new ProvidersProperties();

    @NestedConfigurationProperty
    private GatewayProperties gateway = new GatewayProperties();

    @NestedConfigurationProperty
    private ToolsProperties tools = new ToolsProperties();

    @NestedConfigurationProperty
    private SocialNetworkProperties socialNetwork = new SocialNetworkProperties();

    @NestedConfigurationProperty
    private MCPServersProperties mcpServers = new MCPServersProperties();

    public ModelsProperties getModels() {
        return models;
    }

    public void setModels(ModelsProperties models) {
        this.models = models;
    }

    public AgentProperties getAgent() {
        return agent;
    }

    public void setAgent(AgentProperties agent) {
        this.agent = agent;
    }

    public ChannelsProperties getChannels() {
        return channels;
    }

    public void setChannels(ChannelsProperties channels) {
        this.channels = channels;
    }

    public ProvidersProperties getProviders() {
        return providers;
    }

    public void setProviders(ProvidersProperties providers) {
        this.providers = providers;
    }

    public GatewayProperties getGateway() {
        return gateway;
    }

    public void setGateway(GatewayProperties gateway) {
        this.gateway = gateway;
    }

    public ToolsProperties getTools() {
        return tools;
    }

    public void setTools(ToolsProperties tools) {
        this.tools = tools;
    }

    public SocialNetworkProperties getSocialNetwork() {
        return socialNetwork;
    }

    public void setSocialNetwork(SocialNetworkProperties socialNetwork) {
        this.socialNetwork = socialNetwork;
    }

    public MCPServersProperties getMcpServers() {
        return mcpServers;
    }

    public void setMcpServers(MCPServersProperties mcpServers) {
        this.mcpServers = mcpServers;
    }

    public static class ModelsProperties {
        private Map<String, ModelDefinition> definitions = new HashMap<>();

        public Map<String, ModelDefinition> getDefinitions() {
            return definitions;
        }

        public void setDefinitions(Map<String, ModelDefinition> definitions) {
            this.definitions = definitions;
        }

        public static class ModelDefinition {
            private String provider;
            private String model;
            private Integer maxContextSize;
            private String description;

            public String getProvider() {
                return provider;
            }

            public void setProvider(String provider) {
                this.provider = provider;
            }

            public String getModel() {
                return model;
            }

            public void setModel(String model) {
                this.model = model;
            }

            public Integer getMaxContextSize() {
                return maxContextSize;
            }

            public void setMaxContextSize(Integer maxContextSize) {
                this.maxContextSize = maxContextSize;
            }

            public String getDescription() {
                return description;
            }

            public void setDescription(String description) {
                this.description = description;
            }
        }
    }

    public static class AgentProperties {
        private String workspace;
        private String model;
        private String provider;
        private Integer maxTokens;
        private Double temperature;
        private Integer maxToolIterations;
        private Boolean heartbeatEnabled;
        private Boolean restrictToWorkspace;
        private List<String> commandBlacklist = new ArrayList<>();
        
        @NestedConfigurationProperty
        private EvolutionProperties evolution = new EvolutionProperties();
        
        @NestedConfigurationProperty
        private CollaborationSettingsProperties collaboration = new CollaborationSettingsProperties();

        public String getWorkspace() {
            return workspace;
        }

        public void setWorkspace(String workspace) {
            this.workspace = workspace;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public Integer getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
        }

        public Double getTemperature() {
            return temperature;
        }

        public void setTemperature(Double temperature) {
            this.temperature = temperature;
        }

        public Integer getMaxToolIterations() {
            return maxToolIterations;
        }

        public void setMaxToolIterations(Integer maxToolIterations) {
            this.maxToolIterations = maxToolIterations;
        }

        public Boolean getHeartbeatEnabled() {
            return heartbeatEnabled;
        }

        public void setHeartbeatEnabled(Boolean heartbeatEnabled) {
            this.heartbeatEnabled = heartbeatEnabled;
        }

        public Boolean getRestrictToWorkspace() {
            return restrictToWorkspace;
        }

        public void setRestrictToWorkspace(Boolean restrictToWorkspace) {
            this.restrictToWorkspace = restrictToWorkspace;
        }

        public List<String> getCommandBlacklist() {
            return commandBlacklist;
        }

        public void setCommandBlacklist(List<String> commandBlacklist) {
            this.commandBlacklist = commandBlacklist;
        }

        public EvolutionProperties getEvolution() {
            return evolution;
        }

        public void setEvolution(EvolutionProperties evolution) {
            this.evolution = evolution;
        }

        public CollaborationSettingsProperties getCollaboration() {
            return collaboration;
        }

        public void setCollaboration(CollaborationSettingsProperties collaboration) {
            this.collaboration = collaboration;
        }

        public static class EvolutionProperties {
            private Boolean feedbackEnabled;
            private Integer feedbackRetentionDays;
            private Boolean implicitFeedbackEnabled;
            private Double toolSuccessWeight;
            private Double retryPenaltyWeight;
            private Double sessionLengthWeight;
            private Boolean promptOptimizationEnabled;
            private EvolutionConfig.OptimizationStrategy optimizationStrategy;
            private Integer optimizationIntervalHours;
            private Double adoptionThreshold;
            private Boolean autoApplyOptimization;
            private Double optimizationTemperature;
            private Integer optimizationMaxTokens;
            private Integer maxHistoryVersions;
            private Integer selfRefineSessionCount;

            public Boolean getFeedbackEnabled() {
                return feedbackEnabled;
            }

            public void setFeedbackEnabled(Boolean feedbackEnabled) {
                this.feedbackEnabled = feedbackEnabled;
            }

            public Integer getFeedbackRetentionDays() {
                return feedbackRetentionDays;
            }

            public void setFeedbackRetentionDays(Integer feedbackRetentionDays) {
                this.feedbackRetentionDays = feedbackRetentionDays;
            }

            public Boolean getImplicitFeedbackEnabled() {
                return implicitFeedbackEnabled;
            }

            public void setImplicitFeedbackEnabled(Boolean implicitFeedbackEnabled) {
                this.implicitFeedbackEnabled = implicitFeedbackEnabled;
            }

            public Double getToolSuccessWeight() {
                return toolSuccessWeight;
            }

            public void setToolSuccessWeight(Double toolSuccessWeight) {
                this.toolSuccessWeight = toolSuccessWeight;
            }

            public Double getRetryPenaltyWeight() {
                return retryPenaltyWeight;
            }

            public void setRetryPenaltyWeight(Double retryPenaltyWeight) {
                this.retryPenaltyWeight = retryPenaltyWeight;
            }

            public Double getSessionLengthWeight() {
                return sessionLengthWeight;
            }

            public void setSessionLengthWeight(Double sessionLengthWeight) {
                this.sessionLengthWeight = sessionLengthWeight;
            }

            public Boolean getPromptOptimizationEnabled() {
                return promptOptimizationEnabled;
            }

            public void setPromptOptimizationEnabled(Boolean promptOptimizationEnabled) {
                this.promptOptimizationEnabled = promptOptimizationEnabled;
            }

            public EvolutionConfig.OptimizationStrategy getOptimizationStrategy() {
                return optimizationStrategy;
            }

            public void setOptimizationStrategy(EvolutionConfig.OptimizationStrategy optimizationStrategy) {
                this.optimizationStrategy = optimizationStrategy;
            }

            public Integer getOptimizationIntervalHours() {
                return optimizationIntervalHours;
            }

            public void setOptimizationIntervalHours(Integer optimizationIntervalHours) {
                this.optimizationIntervalHours = optimizationIntervalHours;
            }

            public Double getAdoptionThreshold() {
                return adoptionThreshold;
            }

            public void setAdoptionThreshold(Double adoptionThreshold) {
                this.adoptionThreshold = adoptionThreshold;
            }

            public Boolean getAutoApplyOptimization() {
                return autoApplyOptimization;
            }

            public void setAutoApplyOptimization(Boolean autoApplyOptimization) {
                this.autoApplyOptimization = autoApplyOptimization;
            }

            public Double getOptimizationTemperature() {
                return optimizationTemperature;
            }

            public void setOptimizationTemperature(Double optimizationTemperature) {
                this.optimizationTemperature = optimizationTemperature;
            }

            public Integer getOptimizationMaxTokens() {
                return optimizationMaxTokens;
            }

            public void setOptimizationMaxTokens(Integer optimizationMaxTokens) {
                this.optimizationMaxTokens = optimizationMaxTokens;
            }

            public Integer getMaxHistoryVersions() {
                return maxHistoryVersions;
            }

            public void setMaxHistoryVersions(Integer maxHistoryVersions) {
                this.maxHistoryVersions = maxHistoryVersions;
            }

            public Integer getSelfRefineSessionCount() {
                return selfRefineSessionCount;
            }

            public void setSelfRefineSessionCount(Integer selfRefineSessionCount) {
                this.selfRefineSessionCount = selfRefineSessionCount;
            }
        }

        public static class CollaborationSettingsProperties {
            private Boolean enabled;
            private Integer defaultMaxRounds;
            private Double defaultConsensusThreshold;
            private Long timeoutMs;
            private Map<String, List<RoleTemplateProperties>> roleTemplates = new HashMap<>();

            public Boolean getEnabled() {
                return enabled;
            }

            public void setEnabled(Boolean enabled) {
                this.enabled = enabled;
            }

            public Integer getDefaultMaxRounds() {
                return defaultMaxRounds;
            }

            public void setDefaultMaxRounds(Integer defaultMaxRounds) {
                this.defaultMaxRounds = defaultMaxRounds;
            }

            public Double getDefaultConsensusThreshold() {
                return defaultConsensusThreshold;
            }

            public void setDefaultConsensusThreshold(Double defaultConsensusThreshold) {
                this.defaultConsensusThreshold = defaultConsensusThreshold;
            }

            public Long getTimeoutMs() {
                return timeoutMs;
            }

            public void setTimeoutMs(Long timeoutMs) {
                this.timeoutMs = timeoutMs;
            }

            public Map<String, List<RoleTemplateProperties>> getRoleTemplates() {
                return roleTemplates;
            }

            public void setRoleTemplates(Map<String, List<RoleTemplateProperties>> roleTemplates) {
                this.roleTemplates = roleTemplates;
            }

            public static class RoleTemplateProperties {
                private String name;
                private String prompt;
                private String model;

                public String getName() {
                    return name;
                }

                public void setName(String name) {
                    this.name = name;
                }

                public String getPrompt() {
                    return prompt;
                }

                public void setPrompt(String prompt) {
                    this.prompt = prompt;
                }

                public String getModel() {
                    return model;
                }

                public void setModel(String model) {
                    this.model = model;
                }
            }
        }
    }

    public static class ChannelsProperties {
        @NestedConfigurationProperty
        private TelegramProperties telegram = new TelegramProperties();
        
        @NestedConfigurationProperty
        private DiscordProperties discord = new DiscordProperties();
        
        @NestedConfigurationProperty
        private WhatsAppProperties whatsapp = new WhatsAppProperties();
        
        @NestedConfigurationProperty
        private FeishuProperties feishu = new FeishuProperties();
        
        @NestedConfigurationProperty
        private DingTalkProperties dingtalk = new DingTalkProperties();
        
        @NestedConfigurationProperty
        private QQProperties qq = new QQProperties();
        
        @NestedConfigurationProperty
        private MaixCamProperties maixcam = new MaixCamProperties();

        public TelegramProperties getTelegram() {
            return telegram;
        }

        public void setTelegram(TelegramProperties telegram) {
            this.telegram = telegram;
        }

        public DiscordProperties getDiscord() {
            return discord;
        }

        public void setDiscord(DiscordProperties discord) {
            this.discord = discord;
        }

        public WhatsAppProperties getWhatsapp() {
            return whatsapp;
        }

        public void setWhatsapp(WhatsAppProperties whatsapp) {
            this.whatsapp = whatsapp;
        }

        public FeishuProperties getFeishu() {
            return feishu;
        }

        public void setFeishu(FeishuProperties feishu) {
            this.feishu = feishu;
        }

        public DingTalkProperties getDingtalk() {
            return dingtalk;
        }

        public void setDingtalk(DingTalkProperties dingtalk) {
            this.dingtalk = dingtalk;
        }

        public QQProperties getQq() {
            return qq;
        }

        public void setQq(QQProperties qq) {
            this.qq = qq;
        }

        public MaixCamProperties getMaixcam() {
            return maixcam;
        }

        public void setMaixcam(MaixCamProperties maixcam) {
            this.maixcam = maixcam;
        }

        public static class TelegramProperties {
            private Boolean enabled;
            private String token;
            private List<String> allowFrom = new ArrayList<>();

            public Boolean getEnabled() {
                return enabled;
            }

            public void setEnabled(Boolean enabled) {
                this.enabled = enabled;
            }

            public String getToken() {
                return token;
            }

            public void setToken(String token) {
                this.token = token;
            }

            public List<String> getAllowFrom() {
                return allowFrom;
            }

            public void setAllowFrom(List<String> allowFrom) {
                this.allowFrom = allowFrom;
            }
        }

        public static class DiscordProperties {
            private Boolean enabled;
            private String token;
            private List<String> allowFrom = new ArrayList<>();

            public Boolean getEnabled() {
                return enabled;
            }

            public void setEnabled(Boolean enabled) {
                this.enabled = enabled;
            }

            public String getToken() {
                return token;
            }

            public void setToken(String token) {
                this.token = token;
            }

            public List<String> getAllowFrom() {
                return allowFrom;
            }

            public void setAllowFrom(List<String> allowFrom) {
                this.allowFrom = allowFrom;
            }
        }

        public static class WhatsAppProperties {
            private Boolean enabled;
            private String bridgeUrl;
            private List<String> allowFrom = new ArrayList<>();

            public Boolean getEnabled() {
                return enabled;
            }

            public void setEnabled(Boolean enabled) {
                this.enabled = enabled;
            }

            public String getBridgeUrl() {
                return bridgeUrl;
            }

            public void setBridgeUrl(String bridgeUrl) {
                this.bridgeUrl = bridgeUrl;
            }

            public List<String> getAllowFrom() {
                return allowFrom;
            }

            public void setAllowFrom(List<String> allowFrom) {
                this.allowFrom = allowFrom;
            }
        }

        public static class FeishuProperties {
            private Boolean enabled;
            private String appId;
            private String appSecret;
            private String encryptKey;
            private String verificationToken;
            private String connectionMode;
            private List<String> allowFrom = new ArrayList<>();

            public Boolean getEnabled() {
                return enabled;
            }

            public void setEnabled(Boolean enabled) {
                this.enabled = enabled;
            }

            public String getAppId() {
                return appId;
            }

            public void setAppId(String appId) {
                this.appId = appId;
            }

            public String getAppSecret() {
                return appSecret;
            }

            public void setAppSecret(String appSecret) {
                this.appSecret = appSecret;
            }

            public String getEncryptKey() {
                return encryptKey;
            }

            public void setEncryptKey(String encryptKey) {
                this.encryptKey = encryptKey;
            }

            public String getVerificationToken() {
                return verificationToken;
            }

            public void setVerificationToken(String verificationToken) {
                this.verificationToken = verificationToken;
            }

            public String getConnectionMode() {
                return connectionMode;
            }

            public void setConnectionMode(String connectionMode) {
                this.connectionMode = connectionMode;
            }

            public List<String> getAllowFrom() {
                return allowFrom;
            }

            public void setAllowFrom(List<String> allowFrom) {
                this.allowFrom = allowFrom;
            }
        }

        public static class DingTalkProperties {
            private Boolean enabled;
            private String clientId;
            private String clientSecret;
            private String webhook;
            private String connectionMode;
            private List<String> allowFrom = new ArrayList<>();

            public Boolean getEnabled() {
                return enabled;
            }

            public void setEnabled(Boolean enabled) {
                this.enabled = enabled;
            }

            public String getClientId() {
                return clientId;
            }

            public void setClientId(String clientId) {
                this.clientId = clientId;
            }

            public String getClientSecret() {
                return clientSecret;
            }

            public void setClientSecret(String clientSecret) {
                this.clientSecret = clientSecret;
            }

            public String getWebhook() {
                return webhook;
            }

            public void setWebhook(String webhook) {
                this.webhook = webhook;
            }

            public String getConnectionMode() {
                return connectionMode;
            }

            public void setConnectionMode(String connectionMode) {
                this.connectionMode = connectionMode;
            }

            public List<String> getAllowFrom() {
                return allowFrom;
            }

            public void setAllowFrom(List<String> allowFrom) {
                this.allowFrom = allowFrom;
            }
        }

        public static class QQProperties {
            private Boolean enabled;
            private String appId;
            private String appSecret;
            private List<String> allowFrom = new ArrayList<>();

            public Boolean getEnabled() {
                return enabled;
            }

            public void setEnabled(Boolean enabled) {
                this.enabled = enabled;
            }

            public String getAppId() {
                return appId;
            }

            public void setAppId(String appId) {
                this.appId = appId;
            }

            public String getAppSecret() {
                return appSecret;
            }

            public void setAppSecret(String appSecret) {
                this.appSecret = appSecret;
            }

            public List<String> getAllowFrom() {
                return allowFrom;
            }

            public void setAllowFrom(List<String> allowFrom) {
                this.allowFrom = allowFrom;
            }
        }

        public static class MaixCamProperties {
            private Boolean enabled;
            private String host;
            private Integer port;
            private List<String> allowFrom = new ArrayList<>();

            public Boolean getEnabled() {
                return enabled;
            }

            public void setEnabled(Boolean enabled) {
                this.enabled = enabled;
            }

            public String getHost() {
                return host;
            }

            public void setHost(String host) {
                this.host = host;
            }

            public Integer getPort() {
                return port;
            }

            public void setPort(Integer port) {
                this.port = port;
            }

            public List<String> getAllowFrom() {
                return allowFrom;
            }

            public void setAllowFrom(List<String> allowFrom) {
                this.allowFrom = allowFrom;
            }
        }
    }

    public static class ProvidersProperties {
        @NestedConfigurationProperty
        private ProviderConfigProperties openrouter = new ProviderConfigProperties();
        
        @NestedConfigurationProperty
        private ProviderConfigProperties anthropic = new ProviderConfigProperties();
        
        @NestedConfigurationProperty
        private ProviderConfigProperties openai = new ProviderConfigProperties();
        
        @NestedConfigurationProperty
        private ProviderConfigProperties zhipu = new ProviderConfigProperties();
        
        @NestedConfigurationProperty
        private ProviderConfigProperties gemini = new ProviderConfigProperties();
        
        @NestedConfigurationProperty
        private ProviderConfigProperties dashscope = new ProviderConfigProperties();
        
        @NestedConfigurationProperty
        private ProviderConfigProperties ollama = new ProviderConfigProperties();

        public ProviderConfigProperties getOpenrouter() {
            return openrouter;
        }

        public void setOpenrouter(ProviderConfigProperties openrouter) {
            this.openrouter = openrouter;
        }

        public ProviderConfigProperties getAnthropic() {
            return anthropic;
        }

        public void setAnthropic(ProviderConfigProperties anthropic) {
            this.anthropic = anthropic;
        }

        public ProviderConfigProperties getOpenai() {
            return openai;
        }

        public void setOpenai(ProviderConfigProperties openai) {
            this.openai = openai;
        }

        public ProviderConfigProperties getZhipu() {
            return zhipu;
        }

        public void setZhipu(ProviderConfigProperties zhipu) {
            this.zhipu = zhipu;
        }

        public ProviderConfigProperties getGemini() {
            return gemini;
        }

        public void setGemini(ProviderConfigProperties gemini) {
            this.gemini = gemini;
        }

        public ProviderConfigProperties getDashscope() {
            return dashscope;
        }

        public void setDashscope(ProviderConfigProperties dashscope) {
            this.dashscope = dashscope;
        }

        public ProviderConfigProperties getOllama() {
            return ollama;
        }

        public void setOllama(ProviderConfigProperties ollama) {
            this.ollama = ollama;
        }

        public static class ProviderConfigProperties {
            private String apiKey;
            private String apiBase;

            public String getApiKey() {
                return apiKey;
            }

            public void setApiKey(String apiKey) {
                this.apiKey = apiKey;
            }

            public String getApiBase() {
                return apiBase;
            }

            public void setApiBase(String apiBase) {
                this.apiBase = apiBase;
            }
        }
    }

    public static class GatewayProperties {
        private Boolean enabled;
        private String host;
        private Integer port;
        private String username;
        private String password;
        private String corsOrigin;
        private Integer rateLimitPerMinute;

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public Integer getPort() {
            return port;
        }

        public void setPort(Integer port) {
            this.port = port;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getCorsOrigin() {
            return corsOrigin;
        }

        public void setCorsOrigin(String corsOrigin) {
            this.corsOrigin = corsOrigin;
        }

        public Integer getRateLimitPerMinute() {
            return rateLimitPerMinute;
        }

        public void setRateLimitPerMinute(Integer rateLimitPerMinute) {
            this.rateLimitPerMinute = rateLimitPerMinute;
        }
    }

    public static class ToolsProperties {
        @NestedConfigurationProperty
        private WebToolsProperties web = new WebToolsProperties();
        
        @NestedConfigurationProperty
        private SkillsToolProperties skills = new SkillsToolProperties();

        public WebToolsProperties getWeb() {
            return web;
        }

        public void setWeb(WebToolsProperties web) {
            this.web = web;
        }

        public SkillsToolProperties getSkills() {
            return skills;
        }

        public void setSkills(SkillsToolProperties skills) {
            this.skills = skills;
        }

        public static class WebToolsProperties {
            @NestedConfigurationProperty
            private WebSearchProperties search = new WebSearchProperties();

            public WebSearchProperties getSearch() {
                return search;
            }

            public void setSearch(WebSearchProperties search) {
                this.search = search;
            }

            public static class WebSearchProperties {
                private String apiKey;
                private Integer maxResults;

                public String getApiKey() {
                    return apiKey;
                }

                public void setApiKey(String apiKey) {
                    this.apiKey = apiKey;
                }

                public Integer getMaxResults() {
                    return maxResults;
                }

                public void setMaxResults(Integer maxResults) {
                    this.maxResults = maxResults;
                }
            }
        }

        public static class SkillsToolProperties {
            private List<RegistryConfigProperties> registries = new ArrayList<>();
            private Boolean allowGlobalSearch;
            private String githubToken;

            public List<RegistryConfigProperties> getRegistries() {
                return registries;
            }

            public void setRegistries(List<RegistryConfigProperties> registries) {
                this.registries = registries;
            }

            public Boolean getAllowGlobalSearch() {
                return allowGlobalSearch;
            }

            public void setAllowGlobalSearch(Boolean allowGlobalSearch) {
                this.allowGlobalSearch = allowGlobalSearch;
            }

            public String getGithubToken() {
                return githubToken;
            }

            public void setGithubToken(String githubToken) {
                this.githubToken = githubToken;
            }

            public static class RegistryConfigProperties {
                private String name;
                private String repo;
                private String description;
                private Boolean enabled;

                public String getName() {
                    return name;
                }

                public void setName(String name) {
                    this.name = name;
                }

                public String getRepo() {
                    return repo;
                }

                public void setRepo(String repo) {
                    this.repo = repo;
                }

                public String getDescription() {
                    return description;
                }

                public void setDescription(String description) {
                    this.description = description;
                }

                public Boolean getEnabled() {
                    return enabled;
                }

                public void setEnabled(Boolean enabled) {
                    this.enabled = enabled;
                }
            }
        }
    }

    public static class SocialNetworkProperties {
        private Boolean enabled;
        private String endpoint;
        private String agentId;
        private String apiKey;
        private String agentName;
        private String agentDescription;

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getAgentId() {
            return agentId;
        }

        public void setAgentId(String agentId) {
            this.agentId = agentId;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getAgentName() {
            return agentName;
        }

        public void setAgentName(String agentName) {
            this.agentName = agentName;
        }

        public String getAgentDescription() {
            return agentDescription;
        }

        public void setAgentDescription(String agentDescription) {
            this.agentDescription = agentDescription;
        }
    }

    public static class MCPServersProperties {
        private Boolean enabled;
        private List<MCPServerConfigProperties> servers = new ArrayList<>();

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public List<MCPServerConfigProperties> getServers() {
            return servers;
        }

        public void setServers(List<MCPServerConfigProperties> servers) {
            this.servers = servers;
        }

        public static class MCPServerConfigProperties {
            private String name;
            private String description;
            private String type;
            private String endpoint;
            private String apiKey;
            private String command;
            private List<String> args = new ArrayList<>();
            private Map<String, String> env = new HashMap<>();
            private Boolean enabled;
            private Integer timeout;

            public String getName() {
                return name;
            }

            public void setName(String name) {
                this.name = name;
            }

            public String getDescription() {
                return description;
            }

            public void setDescription(String description) {
                this.description = description;
            }

            public String getType() {
                return type;
            }

            public void setType(String type) {
                this.type = type;
            }

            public String getEndpoint() {
                return endpoint;
            }

            public void setEndpoint(String endpoint) {
                this.endpoint = endpoint;
            }

            public String getApiKey() {
                return apiKey;
            }

            public void setApiKey(String apiKey) {
                this.apiKey = apiKey;
            }

            public String getCommand() {
                return command;
            }

            public void setCommand(String command) {
                this.command = command;
            }

            public List<String> getArgs() {
                return args;
            }

            public void setArgs(List<String> args) {
                this.args = args;
            }

            public Map<String, String> getEnv() {
                return env;
            }

            public void setEnv(Map<String, String> env) {
                this.env = env;
            }

            public Boolean getEnabled() {
                return enabled;
            }

            public void setEnabled(Boolean enabled) {
                this.enabled = enabled;
            }

            public Integer getTimeout() {
                return timeout;
            }

            public void setTimeout(Integer timeout) {
                this.timeout = timeout;
            }
        }
    }
}
