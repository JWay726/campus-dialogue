package com.campus.dialogue.service;

import com.campus.dialogue.config.AppProperties;
import com.campus.dialogue.entity.DocumentEntity;
import com.campus.dialogue.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 知识库文档的持久化与运营：兜底种子、CRUD、临时录入、文件上传。 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final DocumentRepository documentRepository;
    private final RetrievalService retrievalService;
    private final VectorStore vectorStore;
    private final AppProperties props;
    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    public DocumentService(DocumentRepository documentRepository, RetrievalService retrievalService,
                           VectorStore vectorStore, AppProperties props) {
        this.documentRepository = documentRepository;
        this.retrievalService = retrievalService;
        this.vectorStore = vectorStore;
        this.props = props;
    }

    /** 文档总数（与向量片段数一起用于后台对账）。 */
    public long count() {
        return documentRepository.count();
    }

    /**
     * 首次启动兜底：documents 表为空且 {@code campus.rag.seed-on-empty=true} 时，
     * 从 classpath 的 campus-docs 写入示例文档。
     * 默认关闭 —— 否则管理员把知识库删空后一重启示例文档就"复活"，看起来像删除没生效。
     */
    public void seedIfEmpty() {
        if (documentRepository.count() > 0) {
            return;
        }
        if (!props.getRag().isSeedOnEmpty()) {
            log.info("知识库为空，seed-on-empty 未开启，跳过示例文档灌入（后台可手动「导入示例库」）");
            return;
        }
        seedFromClasspath();
    }

    /**
     * 手动把 classpath:campus-docs 的示例文档导入知识库（后台「导入示例库」按钮）。
     * 已存在同来源（同名文件）的文档跳过，不会重复写入。
     *
     * @return 实际新增的文档数
     */
    @Transactional
    public int seedFromClasspath() {
        int added = 0;
        try {
            Resource[] resources = resolver.getResources("classpath:campus-docs/*");
            for (Resource r : resources) {
                if (!r.exists() || r.contentLength() == 0) {
                    continue;
                }
                String name = r.getFilename();
                if (name != null && documentRepository.findFirstBySource(name).isPresent()) {
                    continue;
                }
                String text = new String(r.getContentAsByteArray(), StandardCharsets.UTF_8);
                String title = name == null ? "未命名" : name.replaceAll("\\.md$", "").replace('-', ' ');
                long now = Instant.now().toEpochMilli();
                DocumentEntity saved = documentRepository.save(new DocumentEntity(title, text, name, now, now));
                indexByDoc(saved);
                added++;
            }
            log.info("已从 classpath(campus-docs) 导入示例文档 {} 篇", added);
        } catch (Exception e) {
            log.warn("示例文档导入失败（可忽略，仅影响示例知识库）: {}", e.getMessage());
        }
        return added;
    }

    public List<DocumentEntity> list() {
        return documentRepository.findAllByOrderByUpdatedAtDesc();
    }

    public DocumentEntity get(Long id) {
        return documentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在: " + id));
    }

    /**
     * 清空整个知识库：删除全部文档及其向量片段（内存 + DB）。
     * 用于「我要彻底换一批资料」的场景，避免残留片段继续参与检索。
     *
     * @return 删除的文档数
     */
    @Transactional
    public long wipeAll() {
        long docs = documentRepository.count();
        int chunks = vectorStore.size();
        vectorStore.clear();
        documentRepository.deleteAll();
        log.info("已清空知识库：删除文档 {} 篇、向量片段 {}", docs, chunks);
        return docs;
    }

    /**
     * 新建文档；若已有正文完全相同的文档，则覆盖该文档（改标题/正文/时间）并重新索引，
     * 不再新增重复条目（后台「+新建」粘贴同一份资料时不会再出现两条）。
     */
    @Transactional
    public DocumentEntity create(String title, String content) {
        long now = Instant.now().toEpochMilli();
        Optional<DocumentEntity> identical = findIdenticalContent(content);
        if (identical.isPresent()) {
            DocumentEntity d = identical.get();
            d.setTitle(title);
            d.setContent(content);
            d.setUpdatedAt(now);
            DocumentEntity saved = documentRepository.save(d);
            indexByDoc(saved);
            log.info("新建内容的正文与已有文档 id={} 完全相同，已覆盖该文档（不新增重复条目）", saved.getId());
            return saved;
        }
        DocumentEntity saved = documentRepository.save(new DocumentEntity(title, content, "manual", now, now));
        indexByDoc(saved);
        return saved;
    }

    @Transactional
    public DocumentEntity update(Long id, String title, String content) {
        DocumentEntity d = get(id);
        d.setTitle(title);
        d.setContent(content);
        d.setUpdatedAt(Instant.now().toEpochMilli());
        DocumentEntity saved = documentRepository.save(d);
        indexByDoc(saved);
        return saved;
    }

    /**
     * 删除文档及其向量片段；向量删除是基于实体的事务操作，必须与文档删除同事务。
     * 先校验文档存在：不存在直接抛异常（前端会明确提示失败），避免"看起来删了其实没删"。
     *
     * @return 被一并清理掉的向量片段数
     */
    @Transactional
    public int delete(Long id) {
        get(id); // 不存在则抛 IllegalArgumentException → 404
        int removed = vectorStore.removeByDocId("doc-" + id);
        documentRepository.deleteById(id);
        log.info("已删除文档 id={}，同时清理向量片段 {} 个", id, removed);
        return removed;
    }

    /** 临时录入：保存为 adhoc 文档并立即按文档维度向量化（先清后插，避免重复片段）。 */
    @Transactional
    public DocumentEntity addAdhoc(String text) {
        String trimmed = text.trim();
        String title = trimmed.length() > 20 ? trimmed.substring(0, 20)
                : (trimmed.isEmpty() ? "临时片段" : trimmed);
        long now = Instant.now().toEpochMilli();
        DocumentEntity saved = documentRepository.save(new DocumentEntity(title, text, "adhoc", now, now));
        indexByDoc(saved);
        return saved;
    }

    /**
     * 文件上传：同名文件（source = {@code upload:文件名}）或正文完全相同的文档视为同一篇，
     * 覆盖其正文并重新索引，不再新增重复文档；标题保留原文档（后台改过的标题不会被文件名冲掉）。
     */
    @Transactional
    public DocumentEntity upload(String filename, String content) {
        String source = "upload:" + filename;
        String title = filename == null ? "未命名" : filename.replaceAll("\\.[^.]+$", "");
        if (title.isBlank()) title = "未命名";
        long now = Instant.now().toEpochMilli();

        DocumentEntity target = documentRepository.findFirstBySource(source)
                .or(() -> findIdenticalContent(content))
                .orElse(null);
        if (target != null) {
            target.setContent(content);
            target.setUpdatedAt(now);
            DocumentEntity saved = documentRepository.save(target);
            indexByDoc(saved);
            log.info("上传内容与已有文档 id={} 相同（同名文件或正文一致），已覆盖该文档（不新增重复条目）",
                    saved.getId());
            return saved;
        }
        DocumentEntity saved = documentRepository.save(new DocumentEntity(title, content, source, now, now));
        indexByDoc(saved);
        return saved;
    }

    /** 正文完全相同的已有文档（用于去重）；空正文不参与去重，避免所有空文档互相覆盖。 */
    private Optional<DocumentEntity> findIdenticalContent(String content) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        return documentRepository.findAll().stream()
                .filter(d -> content.equals(d.getContent()))
                .findFirst();
    }

    /** 按文档维度（doc-{id}）重建向量：先清旧片段，再重新切分向量化，杜绝编辑后堆积重复。 */
    private void indexByDoc(DocumentEntity doc) {
        String docId = "doc-" + doc.getId();
        vectorStore.removeByDocId(docId);
        retrievalService.indexText(doc.getContent(), docId);
    }
}
