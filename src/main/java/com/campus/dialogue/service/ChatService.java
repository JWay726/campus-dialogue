package com.campus.dialogue.service;

import com.campus.dialogue.config.AppProperties;
import com.campus.dialogue.model.ChatMessage;
import com.campus.dialogue.model.Message;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

/** 对话编排：拼接 系统提示 + RAG 资料 + 历史上下文 + 用户问题，流式调用 LLM。 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private static final String SYSTEM_PROMPT =
            "你是「校园智答」，一款面向高校师生的智能对话助手。" +
            "请用简洁、友好的中文回答校园相关问题。" +
            "如果下面的【校园资料】中提供了相关内容，请优先依据资料作答；" +
            "资料未覆盖时，可结合常识回答，但不得编造具体的校园规定、时间或电话号码。" +
            "若完全不知道，请如实说明并建议咨询学校相关部门。\n\n";

    private final SessionService sessionService;
    private final MemoryService memoryService;
    private final RetrievalService retrievalService;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    /** 并发 LLM 流式调用上限：超出排队，避免触发模型服务 429 限流。 */
    private final Semaphore llmSemaphore;

    public ChatService(SessionService sessionService, MemoryService memoryService,
                       RetrievalService retrievalService, LlmClient llmClient,
                       ObjectMapper objectMapper, AppProperties props) {
        this.sessionService = sessionService;
        this.memoryService = memoryService;
        this.retrievalService = retrievalService;
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.llmSemaphore = new Semaphore(Math.max(1, props.getLlm().getMaxConcurrent()));
    }

    /**
     * 流式对话。返回的 SSE 事件流依次为：
     *   event=sources  data=<{context, hasHit, count}>   （始终发送；hasHit=false 表示未检索到相关资料）
     *   event=token    data=<文本增量>                  （逐段推送）
     *   event=error    data=<错误原因>                  （出错时发送）
     *   event=done     data=                          （结束标记）
     * 准备阶段含同步阻塞的向量化调用（embed 内部 block），必须切到 boundedElastic 线程；
     * 并用信号量限制并发 LLM 调用，避免多用户同时问触发模型服务限流。
     */
    public Flux<ServerSentEvent<String>> chat(long sessionId, String userText) {
        return chat(sessionId, userText, true);
    }

    /**
     * @param appendUser 是否把这条提问写入会话。前端「重新回答」时传 false：
     *                   问题已在库中，重发只补一次回答，避免会话里出现重复提问。
     */
    public Flux<ServerSentEvent<String>> chat(long sessionId, String userText, boolean appendUser) {
        return Mono.fromCallable(() -> {
                    llmSemaphore.acquire();
                    return prepareMessages(sessionId, userText, appendUser);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(prepared -> {
                    RetrievalService.RetrieveResult r = prepared.retrieve();
                    Flux<ServerSentEvent<String>> sources = Flux.just(event("sources", toJson(r)));
                    StringBuilder full = new StringBuilder();
                    Flux<ServerSentEvent<String>> tokens = llmClient.streamChat(prepared.messages())
                            .doOnNext(full::append)
                            .map(tok -> event("token", tok))
                            .concatWith(Flux.defer(() -> {
                                String answer = full.toString();
                                if (!answer.isBlank()) {
                                    sessionService.appendMessage(sessionId, "assistant", answer);
                                }
                                return Flux.just(event("done", ""));
                            }))
                            // 出错时不再让流直接断掉，而是下发 error 事件
                            .onErrorResume(e -> {
                                log.warn("LLM 流式调用失败：{}", e.getMessage());
                                String note = friendlyError(e);
                                String partial = full.toString();
                                if (!partial.isBlank()) {
                                    sessionService.appendMessage(sessionId, "assistant",
                                            partial + "\n\n[回复中断：" + note + "]");
                                    note = "\n\n[回复中断：" + note + "]";
                                } else {
                                    sessionService.appendMessage(sessionId, "assistant", note);
                                }
                                return Flux.just(event("error", note), event("done", ""));
                            });
                    return Flux.concat(sources, tokens);
                })
                .doFinally(signal -> llmSemaphore.release())
                // 兜底：连“组装问题”这一步就失败（如数据库异常）时，也要给前端一个明确交代
                .onErrorResume(e -> {
                    log.warn("对话准备阶段失败：{}", e.getMessage());
                    return Flux.just(event("error", friendlyError(e)), event("done", ""));
                });
    }

    private ServerSentEvent<String> event(String name, String data) {
        return ServerSentEvent.<String>builder().event(name).data(data).build();
    }

    /** 把检索结果翻译成前端可用的 JSON（含命中标志与命中数）。 */
    private String toJson(RetrievalService.RetrieveResult r) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "context", r.context(),
                    "hasHit", r.hasHit(),
                    "count", r.count()));
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 把底层异常翻译成用户看得懂的一句话。 */
    private String friendlyError(Throwable e) {
        String msg = e == null ? "" : String.valueOf(e.getMessage());
        String lower = msg.toLowerCase();
        if (lower.contains("failed to resolve") || lower.contains("unknownhost") || lower.contains("dns")) {
            return "无法连接模型服务：域名解析失败（DNS 超时），已尝试备用通道仍不可用，请检查网络/VPN 或稍后重试。";
        }
        if (lower.contains("timed out") || lower.contains("timeout")) {
            return "模型服务响应超时，请稍后重试。";
        }
        if (lower.contains("401") || lower.contains("unauthorized")) {
            return "模型服务鉴权失败：API Key 无效或已过期。";
        }
        if (lower.contains("402") || lower.contains("insufficient balance") || lower.contains("insufficient_quota")
                || lower.contains("balance")) {
            return "模型服务账户余额不足（402）：请为 API Key 充值，或在配置里换一个可用的模型服务后重试。";
        }
        if (lower.contains("429")) {
            return "模型服务限流，请稍后重试。";
        }
        if (lower.contains("向量化失败")) {
            return msg;
        }
        return "回答生成失败：" + (msg.isBlank() ? "未知错误" : msg);
    }

    /** 拼装本次请求的完整消息列表（含 RAG 上下文与历史），同时返回检索到的上下文供前端展示。 */
    private Prepared prepareMessages(long sessionId, String userText, boolean appendUser) {
        if (appendUser) {
            sessionService.appendMessage(sessionId, "user", userText);
        }

        RetrievalService.RetrieveResult r = retrievalService.retrieve(userText);
        List<Message> history = memoryService.recent(sessionService.history(sessionId));

        List<ChatMessage> messages = new ArrayList<>();
        String sys = SYSTEM_PROMPT + (r.context().isEmpty() ? "" : "【校园资料】\n" + r.context() + "\n");
        messages.add(new ChatMessage("system", sys));
        for (Message m : history) {
            messages.add(new ChatMessage(m.getRole(), m.getContent()));
        }
        // 必须保证「模型看到的最后一条是这次提问」：
        // 正常提问已写入会话（history 末尾即是它），而「重新回答」不重复写入，
        // 若库里末尾不是本次提问（例如上一轮已有回答），就只在本次请求里补上，不落库。
        boolean lastIsQuestion = !messages.isEmpty()
                && "user".equals(messages.get(messages.size() - 1).role())
                && userText.equals(messages.get(messages.size() - 1).content());
        if (!lastIsQuestion) {
            messages.add(new ChatMessage("user", userText));
        }
        return new Prepared(messages, r);
    }

    /** 准备结果：消息列表 + 检索到的知识库上下文（含命中标志）。 */
    private record Prepared(List<ChatMessage> messages, RetrievalService.RetrieveResult retrieve) {
    }
}
