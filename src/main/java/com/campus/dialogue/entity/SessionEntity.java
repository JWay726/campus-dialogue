package com.campus.dialogue.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "sessions", indexes = {
        @Index(name = "idx_sessions_username", columnList = "username")
})
public class SessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 会话归属用户（登录名）。NULL 表示历史遗留/无主会话，任何用户都不可见，避免跨用户串数据。 */
    @Column(name = "username", length = 64)
    private String username;

    private String title;

    private long createdAt;

    private long updatedAt;

    public SessionEntity() {
    }

    public SessionEntity(String username, String title, long createdAt, long updatedAt) {
        this.username = username;
        this.title = title;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
