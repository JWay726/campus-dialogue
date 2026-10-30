package com.campus.dialogue.service;

import com.campus.dialogue.config.AppProperties;
import com.campus.dialogue.model.ChatMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 调用 OpenAI 兼容 /chat/completions 接口，流式返回 token。
 *
 * <p>为保证演示/线上可用性，这里做了三层防护：
 * <ol>
 *   <li>连接与响应头超时：校园网 DNS 抖动时不会无限挂住，能尽快失败并给出提示；</li>
 *   <li>网络类异常自动重试 1 次（仅在尚未吐出任何 token 时，避免内容重复）；</li>
 *   <li>仍未成功则自动切换备用通道（可配置另一家模型服务），对前端透明。</li>
 * </ol>
 */
@Service
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);
    private static final String CHAT_PATH = "/chat/completions";

    private final AppProperties props;
    private final WebClient primaryClient;
    /** 备用通道；未配置（或配置为占位符）时为 null。 */
    private final WebClient fallbackClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 备用通道被启用的次数，便于排查「为什么回答变慢了/风格变了」。 */
    private final AtomicLong fallbackHits = new AtomicLong();

    public LlmClient(AppProperties props) {
        this.props = props;
        AppProperties.Llm llm = props.getLlm();
        this.primaryClient = buildClient();
        this.fallbackClient = llm.getFallback().isUsable() ? buildClient() : null;
        if (fallbackClient != null) {
            log.info("LLM 备用通道已启用：{} / {}", llm.getFallback().getBaseUrl(), llm.getFallback().getModel());
        }
    }

    private WebClient buildClient() {
        AppProperties.Llm llm = props.getLlm();
        HttpClient http = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        Math.max(1000, llm.getConnectTimeoutSeconds() * 1000))
                .responseTimeout(Duration.ofSeconds(Math.max(5, llm.getResponseTimeoutSeconds())));
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(http))
                .build();
    }

    /** 流式对话：主通道优先，失败且在未输出任何内容时自动降级到备用通道。 */
    public Flux<String> streamChat(List<ChatMessage> messages) {
        List<Map<String, String>> msgs = messages.stream()
                .map(m -> Map.of("role", m.role(), "content", m.content()))
                .toList();

        AtomicBoolean emitted = new AtomicBoolean(false);
        AppProperties.Llm llm = props.getLlm();

        Flux<String> primary = call(primaryClient, llm.getBaseUrl(), llm.getModel(), llm.getApiKey(), msgs)
                // 网络抖动的重试放在「自己这根链路内」：只有还没输出内容时才有意义
                .retryWhen(Retry.backoff(1, Duration.ofMillis(800))
                        .filter(e -> !emitted.get() && isTransient(e)))
                .doOnNext(t -> emitted.set(true));

        if (fallbackClient == null) {
            return primary;
        }
        AppProperties.Llm.Fallback fb = llm.getFallback();
        return primary.onErrorResume(e -> {
            if (emitted.get()) {
                // 已经吐过内容，再切会重复，直接上报
                return Flux.error(e);
            }
            long n = fallbackHits.incrementAndGet();
            log.warn("主通道 {} 失败（{}），自动切换备用通道 {}（第 {} 次）",
                    llm.getModel(), rootMessage(e), fb.getModel(), n);
            return call(fallbackClient, fb.getBaseUrl(), fb.getModel(), fb.getApiKey(), msgs);
        });
    }

    /** 备用通道启用次数（仅用于日志/排障）。 */
    public long fallbackHits() {
        return fallbackHits.get();
    }

    private Flux<String> call(WebClient client, String baseUrl, String model, String apiKey,
                              List<Map<String, String>> msgs) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", msgs);
        body.put("stream", true);
        body.put("temperature", props.getLlm().getTemperature());
        body.put("max_tokens", props.getLlm().getMaxTokens());

        return client.post()
                .uri(joinUrl(baseUrl, CHAT_PATH))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                // 默认异常只带状态码，把响应体（如 "Insufficient Balance"）也带出来，便于定位与翻译
                .onStatus(HttpStatusCode::isError, resp -> resp.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(b -> Mono.error(new LlmHttpException(resp.statusCode().value(), b))))
                .bodyToFlux(String.class)
                .flatMap(s -> Flux.fromArray(s.split("\n")))
                .map(this::extractContent)
                .filter(s -> s != null && !s.isEmpty());
    }

    /** 拼接地址：baseUrl 末尾有无 "/" 都兼容（DeepSeek 用根路径，SiliconFlow 用 /v1）。 */
    private static String joinUrl(String baseUrl, String path) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        if (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b + path;
    }

    /** 是否是「重试一次有机会成功」的网络类错误（DNS/连接/超时）；4xx 不重试。 */
    private static boolean isTransient(Throwable e) {
        if (e instanceof LlmHttpException http) {
            return http.status() >= 500;
        }
        String msg = rootMessage(e).toLowerCase();
        return msg.contains("failed to resolve") || msg.contains("unknownhost")
                || msg.contains("dns") || msg.contains("timed out") || msg.contains("timeout")
                || msg.contains("connection reset") || msg.contains("connection refused")
                || msg.contains("connection prematurely closed");
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    /** 从一行 SSE 数据中解析出 delta.content。兼容"data: {...}"与已去前缀两种形态。 */
    private String extractContent(String line) {
        if (line == null) {
            return "";
        }
        String data = line.trim();
        if (data.startsWith("data:")) {
            data = data.substring(5).trim();
        }
        if (data.isEmpty() || "[DONE]".equals(data)) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(data);
            JsonNode content = node.path("choices").path(0).path("delta").path("content");
            // 注意 delta.content 可能是 JSON null（如末尾的 usage 帧），asText() 会返回字符串 "null"
            if (content.isMissingNode() || content.isNull()) {
                return "";
            }
            return content.asText();
        } catch (Exception e) {
            return "";
        }
    }

    /** 模型服务返回非 2xx 时抛出，保留状态码与响应体摘要。 */
    public static class LlmHttpException extends RuntimeException {
        private final int status;

        public LlmHttpException(int status, String body) {
            super(buildMessage(status, body));
            this.status = status;
        }

        public int status() {
            return status;
        }

        private static String buildMessage(int status, String body) {
            String b = body == null ? "" : body.replaceAll("\\s+", " ").trim();
            if (b.length() > 200) {
                b = b.substring(0, 200) + "…";
            }
            return "模型服务 HTTP " + status + (b.isEmpty() ? "" : "：" + b);
        }
    }
}
