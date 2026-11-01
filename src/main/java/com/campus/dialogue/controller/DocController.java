package com.campus.dialogue.controller;

import com.campus.dialogue.config.AppProperties;
import com.campus.dialogue.service.DocumentService;
import com.campus.dialogue.service.RetrievalService;
import com.campus.dialogue.service.VectorStore;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/docs")
public class DocController {

    private final RetrievalService retrievalService;
    private final VectorStore vectorStore;
    private final DocumentService documentService;
    private final AppProperties props;

    public DocController(RetrievalService retrievalService, VectorStore vectorStore,
                         DocumentService documentService, AppProperties props) {
        this.retrievalService = retrievalService;
        this.vectorStore = vectorStore;
        this.documentService = documentService;
        this.props = props;
    }

    /** 即时录入一段校园资料：保存为 adhoc 文档并立即向量化（不清空既有库）。 */
    @PostMapping("/index")
    public Mono<Map<String, Object>> index(@RequestBody Map<String, String> body) {
        return Mono.fromCallable(() -> {
            String text = body.get("text");
            if (text != null && !text.isBlank()) {
                documentService.addAdhoc(text);
            }
            return Map.<String, Object>of("indexed", vectorStore.size());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 重建向量索引：清空现有向量库（内存 + DB），从 documents 表全量重建。
     * 用于后台编辑/新增/删除知识库文档后刷新检索效果。
     */
    @PostMapping("/reindex")
    public Mono<Map<String, Object>> reindex() {
        return Mono.fromCallable(() -> {
            int n = retrievalService.indexAll();
            return Map.<String, Object>of("reindexed", n, "chunks", vectorStore.size());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 查看当前知识库规模：文档篇数 + 向量片段数 + RAG 开关（后台对账用）。 */
    @GetMapping("/status")
    public Mono<Map<String, Object>> status() {
        return Mono.fromCallable(() -> Map.<String, Object>of(
                        "documents", documentService.count(),
                        "chunks", vectorStore.size(),
                        "ragEnabled", props.getRag().isEnabled()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 上传知识库文件（.md/.txt 等纯文本）：拆分为多篇文档并即时向量化入库。
     * 使用 WebFlux FilePart（非 servlet MultipartFile）。前端以 multipart/form-data 提交，key 为 "files"。
     * 注：当前仅解析纯文本，PDF/Word 等二进制需额外解析组件，后续可扩展。
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Map<String, Object>> upload(@RequestPart("files") Flux<FilePart> files) {
        return files
                .flatMap(part -> DataBufferUtils.join(part.content())
                        .map(buf -> {
                            String content = buf.toString(StandardCharsets.UTF_8);
                            DataBufferUtils.release(buf);
                            return documentService.upload(part.filename(), content);
                        }))
                .collectList()
                .map(saved -> Map.<String, Object>of(
                        "uploaded", saved.size(),
                        "chunks", vectorStore.size()));
    }
}
