package com.campus.dialogue.controller;

import com.campus.dialogue.entity.DocumentEntity;
import com.campus.dialogue.service.DocumentService;
import org.springframework.web.bind.annotation.*;
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
            documentService.delete(id);
            return Map.<String, Object>of("deleted", id);
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
