package com.campus.dialogue.dto;

/**
 * /api/chat 请求体。
 *
 * @param sessionId 会话 id（为空则新建会话）
 * @param message   用户提问
 * @param retry     是否为「重新回答」：为 true 时不再重复写入这条提问
 */
public record ChatRequest(Long sessionId, String message, Boolean retry) {

    public boolean isRetry() {
        return Boolean.TRUE.equals(retry);
    }
}
