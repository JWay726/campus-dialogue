package com.campus.dialogue.entity;

import jakarta.persistence.*;

/** 知识库文档：可经后台录入/编辑，作为向量索引的唯一数据源。 */
@Entity
@Table(name = "documents")
public class DocumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;

    @Column(columnDefinition = "TEXT")
    private String content;

    /** 来源标记：classpath 种子(文件名) / manual(后台录入) / adhoc(临时片段)。 */
    private String source;

    private long createdAt;

    private long updatedAt;

    public DocumentEntity() {
    }

    public DocumentEntity(String title, String content, String source, long createdAt, long updatedAt) {
        this.title = title;
        this.content = content;
        this.source = source;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
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
