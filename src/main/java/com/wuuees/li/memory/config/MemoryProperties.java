package com.wuuees.li.memory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent-memory")
public class MemoryProperties {

    /**
     * 记忆存储的基目录。
     * 默认：~/.agent-memory
     */
    private String basePath = System.getProperty("user.home") + "/.agent-memory";

    /**
     * 项目名称，用于生成人类可读的存储子目录。
     * 不设置则使用项目路径的 SHA-256 哈希。
     */
    private String projectName;

    /**
     * KAIROS 日志 + 夜间蒸馏配置。
     */
    private Kairos kairos = new Kairos();

    /**
     * 侧查询召回（Phase 2）的 LLM 配置。
     */
    private Llm llm = new Llm();

    public String getBasePath() { return basePath; }
    public void setBasePath(String basePath) { this.basePath = basePath; }

    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }

    public Kairos getKairos() { return kairos; }
    public void setKairos(Kairos kairos) { this.kairos = kairos; }

    public Llm getLlm() { return llm; }
    public void setLlm(Llm llm) { this.llm = llm; }

    public static class Kairos {
        /** 是否启用 KAIROS 日志模式 */
        private boolean enabled = false;

        /** 夜间蒸馏 cron（默认每天凌晨 2:00） */
        private String distillCron = "0 0 2 * * ?";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getDistillCron() { return distillCron; }
        public void setDistillCron(String distillCron) { this.distillCron = distillCron; }
    }

    public static class Llm {
        /** 模型提供商：openai、dashscope、ollama */
        private String provider = "openai";

        /** LLM 提供商的 API 密钥 */
        private String apiKey;

        /** 侧查询模型名称（应使用轻量/便宜的模型） */
        private String model = "gpt-4o-mini";

        /** OpenAI 兼容 API 的 Base URL 覆盖 */
        private String baseUrl;

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    }
}
