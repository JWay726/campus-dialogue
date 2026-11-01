package com.campus.dialogue.service;

import com.campus.dialogue.entity.VectorEntity;
import com.campus.dialogue.model.DocChunk;
import com.campus.dialogue.repository.DocumentRepository;
import com.campus.dialogue.repository.VectorRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/** 轻量向量库：内存检索 + MySQL 持久化（生产可换 Chroma / Milvus / pgvector）。 */
@Service
public class VectorStore {

    private static final Logger log = LoggerFactory.getLogger(VectorStore.class);

    private final VectorRepository vectorRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<DocChunk> chunks = new CopyOnWriteArrayList<>();

    /**
     * 可选注入：用于启动时清理「文档已被删除、但片段还留在库里」的僵尸向量。
     * 为空时（单元测试直接 new）跳过一致性校验。
     */
    @Autowired(required = false)
    private DocumentRepository documentRepository;

    public VectorStore(VectorRepository vectorRepository) {
        this.vectorRepository = vectorRepository;
    }

    /** 应用启动时把已持久化的向量库加载进内存，避免每次重启都重新向量化。 */
    @PostConstruct
    public void loadFromDb() {
        List<VectorEntity> all = vectorRepository.findAll();
        Set<String> validDocIds = existingDocIds();
        for (VectorEntity e : all) {
            String docId = e.getDocId();
            // 文档已被删除、片段却还在库里：不再加载进内存，并从 DB 清掉，避免"删了还能答"。
            if (validDocIds != null && docId != null && !validDocIds.contains(docId)) {
                try {
                    vectorRepository.deleteById(e.getId());
                    log.info("清理僵尸向量片段 id={}（所属文档 {} 已不存在）", e.getId(), docId);
                } catch (Exception ex) {
                    log.warn("僵尸向量片段清理失败 id={}: {}", e.getId(), ex.getMessage());
                }
                continue;
            }
            chunks.add(new DocChunk("v" + e.getId(), docId, e.getText(), parseEmbedding(e.getEmbedding())));
        }
        log.info("已从数据库加载向量片段：{}", chunks.size());
    }

    /** 现存文档的 docId 集合（doc-{id}）；无法判断时返回 null，表示不做一致性过滤。 */
    private Set<String> existingDocIds() {
        if (documentRepository == null) {
            return null;
        }
        try {
            Set<String> ids = new HashSet<>();
            for (Long id : documentRepository.findAllIds()) {
                ids.add("doc-" + id);
            }
            return ids;
        } catch (Exception e) {
            log.warn("读取文档 id 列表失败，跳过僵尸片段校验：{}", e.getMessage());
            return null;
        }
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

    /**
     * 用一批已算好的新片段整体替换现有向量库（内存 + DB）。
     * 重建索引时先把所有片段向量化完再调用，避免“先清空、后失败”把旧索引清空。
     */
    @Transactional
    public void replaceAll(List<DocChunk> fresh) {
        chunks.clear();
        vectorRepository.deleteAll();
        for (DocChunk c : fresh) {
            chunks.add(c);
            vectorRepository.save(new VectorEntity(c.docId(), c.text(), toJson(c.vector())));
        }
    }

    /** 按文档维度删除向量片段（内存 + DB），返回内存中移除的片段数。用于编辑/重建/删除单篇文档。 */
    public int removeByDocId(String docId) {
        if (docId == null) return 0;
        int before = chunks.size();
        chunks.removeIf(c -> docId.equals(c.docId()));
        int removed = before - chunks.size();
        vectorRepository.deleteByDocId(docId);
        return removed;
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
