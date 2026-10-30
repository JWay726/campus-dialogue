package com.campus.dialogue.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 对话会话：包含标题与消息列表。 */
public class Session {

    private final long id;
    private String title;
    private final List<Message> messages = new ArrayList<>();
    private final long createdAt;
    private long updatedAt;

    public Session(long id, String title) {
        this(id, title, Instant.now().toEpochMilli(), Instant.now().toEpochMilli(), new ArrayList<>());
    }

    /** 从持久化实体重建会话（保留原始时间，并附带历史消息）。 */
    public Session(long id, String title, long createdAt, long updatedAt, List<Message> messages) {
        this.id = id;
        this.title = title;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        if (messages != null) {
            this.messages.addAll(messages);
        }
    }

    public long getId() { return id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public List<Message> getMessages() { return messages; }
    public long getCreatedAt() { return createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
    public void touch() { this.updatedAt = Instant.now().toEpochMilli(); }
}
