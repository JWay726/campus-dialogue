package com.campus.dialogue.controller;

import com.campus.dialogue.dto.ChatRequest;
import com.campus.dialogue.service.ChatService;
import com.campus.dialogue.service.SessionService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final SessionService sessionService;

    public ChatController(ChatService chatService, SessionService sessionService) {
        this.chatService = chatService;
        this.sessionService = sessionService;
    }

    /**
     * 发送一条消息，流式（SSE）返回助手回复与检索来源。
     * 事件流：sources（知识库片段）→ token（文本增量）→ done。
     * 响应头 X-Session-Id 携带会话 id。会话强制归属当前登录用户，杜绝跨用户串数据。
     *
     * <p>当前用户由 Spring Security 注入（{@code @AuthenticationPrincipal}），
     * 未登录请求在进入本方法前已被安全链拦截为 401。
     */
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<String>>> chat(@RequestBody ChatRequest req,
                                                             @AuthenticationPrincipal(expression = "username") String me) {
        long sessionId = sessionService.getOrCreate(req.sessionId(), me).getId();
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Session-Id", String.valueOf(sessionId));
        Flux<ServerSentEvent<String>> stream = chatService.chat(sessionId, req.message(), !req.isRetry());
        return ResponseEntity.ok()
                .headers(headers)
                .body(stream);
    }
}
