package com.campus.dialogue.service;

import com.campus.dialogue.entity.DocumentEntity;
import com.campus.dialogue.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/** 知识库文档的持久化与运营：兜底种子、CRUD、临时录入、文件上传。 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final DocumentRepository documentRepository;
    private final RetrievalService retrievalService;
    private final VectorStore vectorStore;
    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    public DocumentService(DocumentRepository documentRepository, RetrievalService retrievalService,
                           VectorStore vectorStore) {
        this.documentRepository = documentRepository;
        this.retrievalService = retrievalService;
        this.vectorStore = vectorStore;
    }

    /** 首次启动兜底：documents 表为空时，从 classpath 的 campus-docs 写入，保证既有知识库不丢。 */
    public void seedIfEmpty() {
        if (documentRepository.count() > 0) {
            return;
        }
        try {
            Resource[] resources = resolver.getResources("classpath:campus-docs/*");
            for (Resource r : resources) {
                if (!r.exists() || r.contentLength() == 0) {
                    continue;
                }
                String text = new String(r.getContentAsByteArray(), StandardCharsets.UTF_8);
                String name = r.getFilename();
                String title = name == null ? "未命名" : name.replaceAll("\\.md$", "").replace('-', ' ');
                long now = Instant.now().toEpochMilli();
                documentRepository.save(new DocumentEntity(title, text, name, now, now));
            }
            log.info("已从 classpath(campus-docs) 初始化知识库文档 {} 篇", documentRepository.count());
        } catch (Exception e) {
            log.warn("种子文档加载失败（可忽略，仅影响初始知识库）: {}", e.getMessage());
        }
    }

    public List<DocumentEntity> list() {
        return documentRepository.findAllByOrderByUpdatedAtDesc();
    }

    public DocumentEntity get(Long id) {
        return documentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在: " + id));
    }

    public DocumentEntity create(String title, String content) {
        long now = Instant.now().toEpochMilli();
        DocumentEntity saved = documentRepository.save(new DocumentEntity(title, content, "manual", now, now));
        indexByDoc(saved);
        return saved;
    }

    public DocumentEntity update(Long id, String title, String content) {
        DocumentEntity d = get(id);
        d.setTitle(title);
        d.setContent(content);
        d.setUpdatedAt(Instant.now().toEpochMilli());
        DocumentEntity saved = documentRepository.save(d);
        indexByDoc(saved);
        return saved;
    }

    public void delete(Long id) {
        // 连同向量片段一并清理，避免僵尸片段参与检索
        vectorStore.removeByDocId("doc-" + id);
        documentRepository.deleteById(id);
    }

    /** 临时录入：保存为 adhoc 文档并立即按文档维度向量化（先清后插，避免重复片段）。 */
    public DocumentEntity addAdhoc(String text) {
        String trimmed = text.trim();
        String title = trimmed.length() > 20 ? trimmed.substring(0, 20)
                : (trimmed.isEmpty() ? "临时片段" : trimmed);
        long now = Instant.now().toEpochMilli();
        DocumentEntity saved = documentRepository.save(new DocumentEntity(title, text, "adhoc", now, now));
        indexByDoc(saved);
        return saved;
    }

    /** 文件上传：以文件名作文档标题，保存为 manual 文档并按文档维度索引。 */
    public DocumentEntity upload(String filename, String content) {
        String title = filename == null ? "未命名" : filename.replaceAll("\\.[^.]+$", "");
        if (title.isBlank()) title = "未命名";
        long now = Instant.now().toEpochMilli();
        DocumentEntity saved = documentRepository.save(new DocumentEntity(title, content, "upload:" + filename, now, now));
        indexByDoc(saved);
        return saved;
    }

    /** 按文档维度（doc-{id}）重建向量：先清旧片段，再重新切分向量化，杜绝编辑后堆积重复。 */
    private void indexByDoc(DocumentEntity doc) {
        String docId = "doc-" + doc.getId();
        vectorStore.removeByDocId(docId);
        retrievalService.indexText(doc.getContent(), docId);
    }
}
