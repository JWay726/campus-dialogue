package com.campus.dialogue.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "messages")
public class MessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private long sessionId;

    private String role;

    @Column(columnDefinition = "TEXT")
    private String content;

    // MySQL 中 TIMESTAMP 为保留字，映射为 ts 列名规避冲突
    @Column(name = "ts")
    private long timestamp;

    public MessageEntity() {
    }

    public MessageEntity(long sessionId, String role, String content, long timestamp) {
        this.sessionId = sessionId;
        this.role = role;
        this.content = content;
        this.timestamp = timestamp;
    }

    public Long getId() {
        return id;
    }

    public long getSessionId() {
        return sessionId;
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
