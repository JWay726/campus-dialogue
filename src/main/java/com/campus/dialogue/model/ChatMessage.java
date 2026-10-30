package com.campus.dialogue.model;

/** 发给 LLM 的单条消息（OpenAI 兼容格式）。 */
public record ChatMessage(String role, String content) {
}
