package com.campus.dialogue.model;

import java.time.Instant;

/** 一次会话中的一条消息（用户或助手）。 */
public class Message {

    private final long id;
    private final String role;
    private final String content;
    private final long timestamp;

    /** 新建消息（时间戳取当前时间）。 */
    public Message(long id, String role, String content) {
        this(id, role, content, Instant.now().toEpochMilli());
    }

    /** 从持久化实体重建消息（保留原始时间戳）。 */
    public Message(long id, String role, String content, long timestamp) {
        this.id = id;
        this.role = role;
        this.content = content;
        this.timestamp = timestamp;
    }

    public long getId() { return id; }
    public String getRole() { return role; }
    public String getContent() { return content; }
    public long getTimestamp() { return timestamp; }
}
