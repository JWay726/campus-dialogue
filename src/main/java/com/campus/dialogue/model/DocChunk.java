package com.campus.dialogue.model;

/** RAG 知识库中的一段文档及其向量。 */
public record DocChunk(String id, String docId, String text, float[] vector) {
}
