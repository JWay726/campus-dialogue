package com.campus.dialogue.service;

import com.campus.dialogue.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

/** 调用 bge 等 OpenAI 兼容 /embeddings 接口，把文本转成向量。 */
@Service
public class EmbeddingService {

    private final WebClient embeddingWebClient;
    private final AppProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EmbeddingService(WebClient embeddingWebClient, AppProperties props) {
        this.embeddingWebClient = embeddingWebClient;
        this.props = props;
    }

    /** 最多尝试 2 次：校园网 DNS 偶发超时（Failed to resolve）时，重试一次通常就能成功。 */
    public float[] embed(String text) {
        Exception last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return doEmbed(text);
            } catch (Exception e) {
                last = e;
                if (attempt == 1) {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        throw new RuntimeException("向量化失败: " + describe(last), last);
    }

    private float[] doEmbed(String text) throws Exception {
        Map<String, Object> body = Map.of(
                "input", List.of(text),
                "model", props.getEmbedding().getModel());
        String resp = embeddingWebClient.post()
                .uri("/v1/embeddings")
                .header("Authorization", "Bearer " + props.getEmbedding().getApiKey())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();
        JsonNode node = objectMapper.readTree(resp);
        JsonNode arr = node.path("data").path(0).path("embedding");
        if (arr.isMissingNode() || arr.size() == 0) {
            throw new IllegalStateException("接口返回中无 embedding 字段");
        }
        float[] vec = new float[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            vec[i] = (float) arr.get(i).asDouble();
        }
        return vec;
    }

    /** 把底层异常翻译成人类可读的原因，避免前端只看到一长串堆栈类名。 */
    private String describe(Exception e) {
        if (e == null) {
            return "未知错误";
        }
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String lower = msg.toLowerCase();
        if (lower.contains("failed to resolve") || lower.contains("unknownhost")
                || lower.contains("dns")) {
            return "无法解析 embedding 服务域名（DNS 解析失败，请检查网络/VPN 或更换 DNS 后重试）";
        }
        if (lower.contains("timed out") || lower.contains("timeout")) {
            return "embedding 服务请求超时（网络不稳定，请稍后重试）";
        }
        if (lower.contains("401") || lower.contains("unauthorized")) {
            return "embedding 服务鉴权失败（API Key 无效或已过期）";
        }
        if (lower.contains("429")) {
            return "embedding 服务限流（请求过于频繁，请稍后重试）";
        }
        return msg;
    }
}
