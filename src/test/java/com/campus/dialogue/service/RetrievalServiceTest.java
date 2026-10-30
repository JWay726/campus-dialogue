package com.campus.dialogue.service;

import com.campus.dialogue.config.AppProperties;
import com.campus.dialogue.model.DocChunk;
import com.campus.dialogue.repository.DocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class RetrievalServiceTest {

    /** 轻量假实现：避免 Mockito 在 JDK 25 上对具体类的 inline mock maker 失败。 */
    static final class FakeEmbed extends EmbeddingService {
        FakeEmbed() { super(null, null); }
        @Override public float[] embed(String text) { return new float[]{1f, 0f, 0f}; }
    }

    /** 假 VectorStore：检索结果由测试直接注入，便于验证阈值过滤逻辑。 */
    static final class FakeVS extends VectorStore {
        List<ScoredChunk> chunks = List.of();
        FakeVS() { super(null); }
        @Override public int size() { return chunks.isEmpty() ? 0 : 1; }
        @Override public List<ScoredChunk> searchWithScores(float[] q, int topK) { return chunks; }
    }

    @Mock
    DocumentRepository docRepo;

    AppProperties props = new AppProperties();
    FakeVS vs = new FakeVS();
    RetrievalService svc;

    @BeforeEach
    void setup() {
        props.getRag().setEnabled(true);
        props.getRag().setTopK(3);
        props.getRag().setSimilarityThreshold(0.3);
        props.getEmbedding().setConcurrency(1);
        props.getMemory(); // 触发嵌套对象初始化，避免 NPE
        svc = new RetrievalService(new FakeEmbed(), vs, docRepo, props);
    }

    @Test
    void filtersBelowThreshold() {
        vs.chunks = List.of(
                new VectorStore.ScoredChunk(new DocChunk("a", "d1", "相关片段", new float[]{1f, 0f, 0f}), 0.9f),
                new VectorStore.ScoredChunk(new DocChunk("b", "d2", "无关片段", new float[]{0f, 1f, 0f}), 0.1f));

        RetrievalService.RetrieveResult r = svc.retrieve("q");
        assertTrue(r.hasHit());
        assertEquals(1, r.count());
        assertTrue(r.context().contains("相关片段"));
        assertFalse(r.context().contains("无关片段"));
    }

    @Test
    void noHitWhenAllBelowThreshold() {
        vs.chunks = List.of(
                new VectorStore.ScoredChunk(new DocChunk("b", "d2", "x", new float[]{0f, 1f, 0f}), 0.1f));
        RetrievalService.RetrieveResult r = svc.retrieve("q");
        assertFalse(r.hasHit());
        assertEquals(0, r.count());
    }

    @Test
    void disabledRagReturnsEmpty() {
        props.getRag().setEnabled(false);
        RetrievalService.RetrieveResult r = svc.retrieve("q");
        assertFalse(r.hasHit());
        assertEquals(0, r.count());
    }
}
