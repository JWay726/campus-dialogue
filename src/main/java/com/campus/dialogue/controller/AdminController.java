package com.campus.dialogue.controller;

import com.campus.dialogue.entity.DocumentEntity;
import com.campus.dialogue.service.DocumentService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

/** 知识库后台管理：文档的列表/详情/新增/修改/删除。阻塞型 JPA 调用统一切到 boundedElastic。 */
@RestController
@RequestMapping("/api/admin/docs")
public class AdminController {

    private final DocumentService documentService;

    public AdminController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @GetMapping
    public Mono<List<DocumentEntity>> list() {
        return Mono.fromCallable(documentService::list).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}")
    public Mono<DocumentEntity> get(@PathVariable Long id) {
        return Mono.fromCallable(() -> documentService.get(id)).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping
    public Mono<DocumentEntity> create(@RequestBody Map<String, String> body) {
        return Mono.fromCallable(() -> documentService.create(
                        body.getOrDefault("title", "未命名文档"),
                        body.getOrDefault("content", "")))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PutMapping("/{id}")
    public Mono<DocumentEntity> update(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return Mono.fromCallable(() -> documentService.update(
                        id, body.getOrDefault("title", ""), body.getOrDefault("content", "")))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/{id}")
    public Mono<Map<String, Object>> delete(@PathVariable Long id) {
        return Mono.fromCallable(() -> {
                    int vectors = documentService.delete(id);
                    return Map.<String, Object>of(
                            "deleted", id,
                            "vectorsCleared", vectors,
                            "documents", documentService.count());
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(IllegalArgumentException.class,
                        e -> new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage()));
    }

    /** 清空整个知识库（文档 + 向量片段）。用于彻底换资料，避免残留片段继续被检索。 */
    @DeleteMapping
    public Mono<Map<String, Object>> wipeAll() {
        return Mono.fromCallable(() -> Map.<String, Object>of(
                        "documentsDeleted", documentService.wipeAll())
                )
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 手动导入 classpath:campus-docs 的示例知识库（同名文件已存在则跳过）。 */
    @PostMapping("/import-samples")
    public Mono<Map<String, Object>> importSamples() {
        return Mono.fromCallable(() -> Map.<String, Object>of(
                        "imported", documentService.seedFromClasspath(),
                        "documents", documentService.count())
                )
                .subscribeOn(Schedulers.boundedElastic());
    }
}
