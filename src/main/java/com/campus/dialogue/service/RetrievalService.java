package com.campus.dialogue.service;

import com.campus.dialogue.config.AppProperties;
import com.campus.dialogue.entity.DocumentEntity;
import com.campus.dialogue.model.DocChunk;
import com.campus.dialogue.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/** RAG 检索：索引校园资料 + 查询时检索相关片段。 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);
    private static final int CHUNK_SIZE = 280;
    private static final int OVERLAP = 40;

    private final EmbeddingService embeddingService;
    private final VectorStore vectorStore;
    private final DocumentRepository documentRepository;
    private final AppProperties props;
    private final AtomicInteger chunkCounter = new AtomicInteger(0);

    public RetrievalService(EmbeddingService embeddingService, VectorStore vectorStore,
                            DocumentRepository documentRepository, AppProperties props) {
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
        this.documentRepository = documentRepository;
        this.props = props;
    }

    /** 检索结果：拼接文本 + 是否命中 + 命中片段数（供前端区分「已检索」与「无相关资料」）。 */
    public record RetrieveResult(String context, boolean hasHit, int count) {}

    /** 从 documents 表全量重建向量库：先清空，再逐篇切分、按文档 docId 并发向量化入库。 */
    public int indexAll() {
        vectorStore.clear();
        for (DocumentEntity d : documentRepository.findAll()) {
            if (d.getContent() == null || d.getContent().isBlank()) {
                continue;
            }
            String docId = "doc-" + d.getId();
            List<DocChunk> chunks = embedChunks(split(d.getContent()), docId);
            for (DocChunk c : chunks) {
                vectorStore.add(new DocChunk("c" + chunkCounter.incrementAndGet(), c.docId(), c.text(), c.vector()));
            }
        }
        log.info("知识库重建完成，当前向量片段：{}", vectorStore.size());
        return vectorStore.size();
    }

    /** 把一段长文本切分后逐块向量化入库（即时生效，不清空既有库）。 */
    public void indexText(String text) {
        indexText(text, null);
    }

    /** 把一段长文本按文档维度 docId 向量化入库（可用于单篇文档重建，避免重复片段）。 */
    public void indexText(String text, String docId) {
        List<DocChunk> chunks = embedChunks(split(text), docId);
        for (DocChunk c : chunks) {
            vectorStore.add(new DocChunk("c" + chunkCounter.incrementAndGet(), c.docId(), c.text(), c.vector()));
        }
    }

    /**
     * 查询：返回检索到的资料片段拼接文本及命中信息；RAG 关闭或未索引时返回空结果。
     * 向量化失败（例如校园网 DNS 偶发超时）时降级为「不使用知识库」，让对话继续由 LLM 常识作答。
     * 命中片段需满足相似度阈值（cosine >= similarityThreshold），低于阈值的噪音片段不注入 prompt。
     */
    public RetrieveResult retrieve(String query) {
        if (!props.getRag().isEnabled() || vectorStore.size() == 0) {
            return new RetrieveResult("", false, 0);
        }
        List<VectorStore.ScoredChunk> hits;
        try {
            float[] q = embeddingService.embed(query);
            hits = vectorStore.searchWithScores(q, props.getRag().getTopK());
        } catch (Exception e) {
            log.warn("向量检索失败，本次降级为不注入知识库直接作答：{}", e.getMessage());
            return new RetrieveResult("", false, 0);
        }
        double threshold = props.getRag().getSimilarityThreshold();
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (VectorStore.ScoredChunk h : hits) {
            if (h.score() < threshold) {
                continue;
            }
            sb.append("- ").append(h.chunk().text()).append("\n");
            count++;
        }
        String ctx = sb.toString().trim();
        return new RetrieveResult(ctx, count > 0, count);
    }

    /** 并发向量化一组文本，返回带向量的片段（短于 8 字忽略）。 */
    private List<DocChunk> embedChunks(List<String> texts, String docId) {
        List<String> trimmed = new ArrayList<>();
        for (String t : texts) {
            String s = t.trim();
            if (s.length() >= 8) {
                trimmed.add(s);
            }
        }
        if (trimmed.isEmpty()) {
            return List.of();
        }
        int pool = Math.max(1, Math.min(props.getEmbedding().getConcurrency(), trimmed.size()));
        ExecutorService executor = Executors.newFixedThreadPool(pool);
        try {
            List<Future<DocChunk>> futures = new ArrayList<>();
            for (String s : trimmed) {
                futures.add(executor.submit(() -> {
                    float[] vec = embeddingService.embed(s);
                    return new DocChunk("c0", docId, s, vec);
                }));
            }
            List<DocChunk> out = new ArrayList<>();
            for (Future<DocChunk> f : futures) {
                out.add(f.get());
            }
            return out;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("向量化被中断", ie);
        } catch (Exception e) {
            throw new RuntimeException("向量化失败: " + describe(e), e);
        } finally {
            executor.shutdownNow();
        }
    }

    /** 把底层异常翻译成人类可读的原因。 */
    private String describe(Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String lower = msg.toLowerCase();
        if (lower.contains("failed to resolve") || lower.contains("unknownhost") || lower.contains("dns")) {
            return "无法解析 embedding 服务域名（DNS 解析失败，请检查网络/VPN 或更换 DNS 后重试）";
        }
        if (lower.contains("timed out") || lower.contains("timeout")) {
            return "embedding 服务请求超时（网络不稳定，请稍后重试）";
        }
        return msg;
    }

    /** 按长度滑动窗口切分，保留少量重叠以不断句。 */
    private List<String> split(String text) {
        List<String> out = new ArrayList<>();
        String clean = text.replace("\r", "");
        int start = 0;
        while (start < clean.length()) {
            int end = Math.min(start + CHUNK_SIZE, clean.length());
            out.add(clean.substring(start, end));
            if (end == clean.length()) {
                break;
            }
            start = end - OVERLAP;
        }
        return out;
    }
}
