package com.campus.dialogue.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "campus")
public class AppProperties {

    private Llm llm = new Llm();
    private Embedding embedding = new Embedding();
    private Rag rag = new Rag();
    private Memory memory = new Memory();
    private Auth auth = new Auth();
    private Wechat wechat = new Wechat();

    public static class Llm {
        private String baseUrl = "https://api.deepseek.com";
        private String apiKey = "sk-xxxx";
        private String model = "deepseek-chat";
        private double temperature = 0.7;
        private int maxTokens = 1024;
        /** 建连超时（秒）。校园网 DNS/TCP 偶发超时，设短一点便于尽快切备用通道。 */
        private int connectTimeoutSeconds = 10;
        /** 等待响应头超时（秒）。只约束「多久没开始回」，不影响流式输出的总时长。 */
        private int responseTimeoutSeconds = 60;
        /** 备用通道：主通道余额不足(402)/限流(429)/域名解析失败时自动切换。 */
        private Fallback fallback = new Fallback();

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
        /** 并发 LLM 流式调用上限，超出排队，避免触发模型服务 429 限流。 */
        private int maxConcurrent = 4;

        public int getMaxConcurrent() { return maxConcurrent; }
        public void setMaxConcurrent(int maxConcurrent) { this.maxConcurrent = maxConcurrent; }

        public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
        public void setConnectTimeoutSeconds(int connectTimeoutSeconds) { this.connectTimeoutSeconds = connectTimeoutSeconds; }
        public int getResponseTimeoutSeconds() { return responseTimeoutSeconds; }
        public void setResponseTimeoutSeconds(int responseTimeoutSeconds) { this.responseTimeoutSeconds = responseTimeoutSeconds; }
        public Fallback getFallback() { return fallback; }
        public void setFallback(Fallback fallback) { this.fallback = fallback; }

        /**
         * 备用模型通道（同样走 OpenAI 兼容 /chat/completions）。
         * 与主通道是「同一份 prompt、同一套协议」，失败时对用户透明地降级，
         * 避免单个 API Key 欠费 / 单条链路 DNS 抖动就让整个问答不可用。
         */
        public static class Fallback {
            private boolean enabled = true;
            private String baseUrl = "https://api.siliconflow.cn/v1";
            private String apiKey = "sk-xxxx";
            private String model = "deepseek-ai/DeepSeek-V3.2";

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
            public String getBaseUrl() { return baseUrl; }
            public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
            public String getApiKey() { return apiKey; }
            public void setApiKey(String apiKey) { this.apiKey = apiKey; }
            public String getModel() { return model; }
            public void setModel(String model) { this.model = model; }

            /** 配置齐全才启用：地址/密钥都不能为空或是占位符 sk-xxxx。 */
            public boolean isUsable() {
                return enabled
                        && baseUrl != null && !baseUrl.isBlank()
                        && apiKey != null && !apiKey.isBlank() && !"sk-xxxx".equals(apiKey)
                        && model != null && !model.isBlank();
            }
        }
    }

    public static class Rag {
        private boolean enabled = true;
        private int topK = 3;
        /** 相似度阈值（余弦）：低于该分数的片段不注入 prompt，避免答非所问/噪声。 */
        private double similarityThreshold = 0.3;
        /**
         * documents 表为空时，是否自动从 classpath:campus-docs 灌入示例文档。
         * 默认关闭：管理员删空知识库后重启不会把示例文档"复活"（需要时在后台点「导入示例库」）。
         */
        private boolean seedOnEmpty = false;

        public boolean isSeedOnEmpty() { return seedOnEmpty; }
        public void setSeedOnEmpty(boolean seedOnEmpty) { this.seedOnEmpty = seedOnEmpty; }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getTopK() { return topK; }
        public void setTopK(int topK) { this.topK = topK; }
        public double getSimilarityThreshold() { return similarityThreshold; }
        public void setSimilarityThreshold(double similarityThreshold) { this.similarityThreshold = similarityThreshold; }
    }

    public static class Memory {
        private int windowSize = 10;
        /** 上下文字符预算：从最近消息往前累加，超过即截断（保留 system + 当前问题余量）。 */
        private int maxChars = 4000;

        public int getWindowSize() { return windowSize; }
        public void setWindowSize(int windowSize) { this.windowSize = windowSize; }
        public int getMaxChars() { return maxChars; }
        public void setMaxChars(int maxChars) { this.maxChars = maxChars; }
    }

    public static class Embedding {
        private String baseUrl = "https://api.siliconflow.cn";
        private String apiKey = "sk-xxxx";
        private String model = "BAAI/bge-m3";
        private int dim = 1024;
        /** 批量向量化的并发度（校园网 DNS 偶发超时，并发能显著缩短大库重建时间）。 */
        private int concurrency = 4;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getDim() { return dim; }
        public void setDim(int dim) { this.dim = dim; }
        public int getConcurrency() { return concurrency; }
        public void setConcurrency(int concurrency) { this.concurrency = concurrency; }
    }

    public Llm getLlm() { return llm; }
    public void setLlm(Llm llm) { this.llm = llm; }
    public Embedding getEmbedding() { return embedding; }
    public void setEmbedding(Embedding embedding) { this.embedding = embedding; }
    public Rag getRag() { return rag; }
    public void setRag(Rag rag) { this.rag = rag; }
    public Memory getMemory() { return memory; }
    public void setMemory(Memory memory) { this.memory = memory; }

    public Auth getAuth() { return auth; }
    public void setAuth(Auth auth) { this.auth = auth; }

    public Wechat getWechat() { return wechat; }
    public void setWechat(Wechat wechat) { this.wechat = wechat; }

    /**
     * 微信开放平台（网站应用扫码登录）配置。
     * 未配置 app-id / app-secret 时，个人中心的微信绑定走「演示模式」：不经微信服务器，
     * 由提交的 code 派生一个稳定的演示 openid，便于本地联调；配置后自动切换为真实 OAuth2 流程。
     */
    public static class Wechat {
        private String appId = "";
        private String appSecret = "";
        private String redirectUri = "http://localhost:8088/profile.html";
        private String loginRedirectUri = "http://localhost:8088/login.html";
        private String scope = "snsapi_login";

        public String getAppId() { return appId; }
        public void setAppId(String appId) { this.appId = appId; }
        public String getAppSecret() { return appSecret; }
        public void setAppSecret(String appSecret) { this.appSecret = appSecret; }
        public String getRedirectUri() { return redirectUri; }
        public void setRedirectUri(String redirectUri) { this.redirectUri = redirectUri; }
        public String getLoginRedirectUri() { return loginRedirectUri; }
        public void setLoginRedirectUri(String loginRedirectUri) { this.loginRedirectUri = loginRedirectUri; }
        public String getScope() { return scope; }
        public void setScope(String scope) { this.scope = scope; }

        /** 是否已具备真实对接条件。 */
        public boolean isConfigured() {
            return appId != null && !appId.isBlank() && appSecret != null && !appSecret.isBlank();
        }
    }

    public static class Auth {
        /**
         * 默认管理员（种子账号）：仅当用户表为空时由 UserService.seedIfEmpty() 灌入。
         * 普通用户不再预置，改为开放注册（默认 USER 角色）。生产务必通过 AUTH_USERNAME/AUTH_PASSWORD 覆盖。
         */
        private Account admin = new Account("admin", "change_me", "ADMIN");

        public Account getAdmin() { return admin; }
        public void setAdmin(Account admin) { this.admin = admin; }

        public static class Account {
            private String username;
            private String password;
            private String role;
            public Account() {}
            public Account(String username, String password, String role) {
                this.username = username; this.password = password; this.role = role;
            }
            public String getUsername() { return username; }
            public void setUsername(String username) { this.username = username; }
            public String getPassword() { return password; }
            public void setPassword(String password) { this.password = password; }
            public String getRole() { return role; }
            public void setRole(String role) { this.role = role; }
        }
    }
}
