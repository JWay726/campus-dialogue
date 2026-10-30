package com.campus.dialogue.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "vectors")
public class VectorEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String docId;

    // TEXT 在 MySQL 中为保留字，映射为 chunk_text 列名规避冲突
    @Column(name = "chunk_text", columnDefinition = "TEXT")
    private String text;

    /** 向量以 JSON 数组字符串存储（如 [0.123,0.456,...]），避免数据库数组类型兼容问题。 */
    @Column(columnDefinition = "TEXT")
    private String embedding;

    public VectorEntity() {
    }

    public VectorEntity(String docId, String text, String embedding) {
        this.docId = docId;
        this.text = text;
        this.embedding = embedding;
    }

    public Long getId() {
        return id;
    }

    public String getDocId() {
        return docId;
    }

    public String getText() {
        return text;
    }

    public String getEmbedding() {
        return embedding;
    }
}
