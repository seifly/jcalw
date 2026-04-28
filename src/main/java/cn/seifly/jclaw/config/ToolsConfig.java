package cn.seifly.jclaw.config;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具配置类
 * 配置各种工具的参数，如网络搜索工具、技能市场等
 */
public class ToolsConfig {
    
    private WebToolsConfig web;
    private SkillsToolConfig skills;
    
    public ToolsConfig() {
        this.web = new WebToolsConfig();
        this.skills = new SkillsToolConfig();
    }
    
    public WebToolsConfig getWeb() {
        return web;
    }
    
    public void setWeb(WebToolsConfig web) {
        this.web = web;
    }
    
    public SkillsToolConfig getSkills() {
        return skills;
    }
    
    public void setSkills(SkillsToolConfig skills) {
        this.skills = skills;
    }
    
    /**
     * 获取 Brave API Key（用于网络搜索）
     */
    @JsonIgnore
    public String getBraveApi() {
        return web != null && web.getSearch() != null ? web.getSearch().getApiKey() : "";
    }
    
    public static class WebToolsConfig {
        private WebSearchConfig search;
        
        public WebToolsConfig() {
            this.search = new WebSearchConfig();
        }
        
        public WebSearchConfig getSearch() {
            return search;
        }
        
        public void setSearch(WebSearchConfig search) {
            this.search = search;
        }
    }
    
    public static class WebSearchConfig {
        private String apiKey;
        private int maxResults;
        
        public WebSearchConfig() {
            this.apiKey = "";
            this.maxResults = 5;
        }
        
        public String getApiKey() {
            return apiKey;
        }
        
        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
        
        public int getMaxResults() {
            return maxResults;
        }
        
        public void setMaxResults(int maxResults) {
            this.maxResults = maxResults;
        }
    }
    
    /**
     * 技能工具配置
     * 
     * 配置技能搜索的市场源和安全策略。
     */
    public static class SkillsToolConfig {
        
        /**
         * 可信技能市场源列表
         * 
         * 每个条目包含 name、repo、description、enabled 字段。
         * 默认包含内置的官方和社区市场源。
         * 用户可以添加自定义的可信源。
         */
        private List<RegistryConfig> registries;
        
        /**
         * 是否允许 GitHub 全网搜索
         * 
         * 默认为 false，只从可信市场源搜索。
         * 设为 true 后，当可信源中找不到匹配技能时，
         * 会降级到 GitHub 全网搜索（带安全警告）。
         */
        private boolean allowGlobalSearch;
        
        /**
         * GitHub Token（可选）
         * 
         * 用于提高 GitHub API 速率限制。
         * 未认证：每分钟 10 次搜索请求
         * 已认证：每分钟 30 次搜索请求
         */
        private String githubToken;
        
        public SkillsToolConfig() {
            this.registries = new ArrayList<>();
            this.allowGlobalSearch = false;
            this.githubToken = "";
        }
        
        public List<RegistryConfig> getRegistries() {
            return registries;
        }
        
        public void setRegistries(List<RegistryConfig> registries) {
            this.registries = registries;
        }
        
        public boolean isAllowGlobalSearch() {
            return allowGlobalSearch;
        }
        
        public void setAllowGlobalSearch(boolean allowGlobalSearch) {
            this.allowGlobalSearch = allowGlobalSearch;
        }
        
        public String getGithubToken() {
            return githubToken;
        }
        
        public void setGithubToken(String githubToken) {
            this.githubToken = githubToken;
        }
    }
    
    /**
     * 单个技能市场源配置
     */
    public static class RegistryConfig {
        private String name;
        private String repo;
        private String description;
        private boolean enabled;
        
        public RegistryConfig() {
            this.enabled = true;
        }
        
        public RegistryConfig(String name, String repo, String description) {
            this.name = name;
            this.repo = repo;
            this.description = description;
            this.enabled = true;
        }
        
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        
        public String getRepo() { return repo; }
        public void setRepo(String repo) { this.repo = repo; }
        
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
