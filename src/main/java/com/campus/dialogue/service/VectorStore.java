package com.campus.dialogue.service;

import com.campus.dialogue.entity.VectorEntity;
import com.campus.dialogue.model.DocChunk;
import com.campus.dialogue.repository.VectorRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 轻量向量库：内存检索 + MySQL 持久化（生产可换 Chroma / Milvus / pgvector）。 */
@Service
public class VectorStore {

    private static final Logger log = LoggerFactory.getLogger(VectorStore.class);

    private final VectorRepository vectorRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<DocChunk> chunks = new CopyOnWriteArrayList<>();

    public VectorStore(VectorRepository vectorRepository) {
        this.vectorRepository = vectorRepository;
    }

    /** 应用启动时把已持久化的向量库加载进内存，避免每次重启都重新向量化。 */
    @PostConstruct
    public void loadFromDb() {
        List<VectorEntity> all = vectorRepository.findAll();
        for (VectorEntity e : all) {
            chunks.add(new DocChunk("v" + e.getId(), e.getDocId(), e.getText(), parseEmbedding(e.getEmbedding())));
        }
        log.info("已从数据库加载向量片段：{}", chunks.size());
    }

    /** 新增一个向量片段（携带文档维度 docId，供按文档去重）。 */
    public void add(DocChunk chunk) {
        chunks.add(chunk);
        vectorRepository.save(new VectorEntity(chunk.docId(), chunk.text(), toJson(chunk.vector())));
    }

    public void clear() {
        chunks.clear();
        vectorRepository.deleteAll();
    }

    /** 按文档维度删除向量片段（内存 + DB），用于编辑/重建单篇文档时去除旧片段。 */
    public void removeByDocId(String docId) {
        if (docId == null) return;
        chunks.removeIf(c -> docId.equals(c.docId()));
        vectorRepository.deleteByDocId(docId);
    }

    public int size() {
        return chunks.size();
    }

    /** 返回与 query 向量最相似的 topK 个片段。 */
    public List<DocChunk> search(float[] query, int topK) {
        List<Scored> scored = new ArrayList<>();
        for (DocChunk c : chunks) {
            scored.add(new Scored(c, cosine(query, c.vector())));
        }
        scored.sort(Comparator.comparingDouble(s -> -s.score));
        int limit = Math.min(topK, scored.size());
        List<DocChunk> result = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            result.add(scored.get(i).chunk);
        }
        return result;
    }

    /** 带相似度分数的检索结果，供上层按阈值过滤。 */
    public record ScoredChunk(DocChunk chunk, float score) {}

    /** 返回与 query 向量最相似的 topK 个片段及其余弦相似度（降序）。 */
    public List<ScoredChunk> searchWithScores(float[] query, int topK) {
        List<ScoredChunk> scored = new ArrayList<>();
        for (DocChunk c : chunks) {
            scored.add(new ScoredChunk(c, cosine(query, c.vector())));
        }
        scored.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
        int limit = Math.min(topK, scored.size());
        return new ArrayList<>(scored.subList(0, limit));
    }

    private String toJson(float[] vec) {
        try {
            return objectMapper.writeValueAsString(vec);
        } catch (Exception e) {
            throw new RuntimeException("向量序列化失败", e);
        }
    }

    private float[] parseEmbedding(String json) {
        try {
            return objectMapper.readValue(json, float[].class);
        } catch (Exception e) {
            log.warn("向量反序列化失败，跳过该片段：{}", e.getMessage());
            return new float[0];
        }
    }

    private static float cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            return 0f;
        }
        float dot = 0f, na = 0f, nb = 0f;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        float denom = (float) (Math.sqrt(na) * Math.sqrt(nb));
        return denom == 0f ? 0f : dot / denom;
    }

    private static class Scored {
        final DocChunk chunk;
        final float score;
        Scored(DocChunk chunk, float score) {
            this.chunk = chunk;
            this.score = score;
        }
    }
}
