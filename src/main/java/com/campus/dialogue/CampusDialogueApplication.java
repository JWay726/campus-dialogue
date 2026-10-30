package com.campus.dialogue;

import com.campus.dialogue.service.DocumentService;
import com.campus.dialogue.service.RetrievalService;
import com.campus.dialogue.service.UserService;
import com.campus.dialogue.service.VectorStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class CampusDialogueApplication {

    public static void main(String[] args) {
        // 校园网 DNS 偶发解析超时，而 JDK 默认会把「解析失败」的结果缓存 10 秒，
        // 一次抖动会被放大成连续 10 秒不可用。这里关掉负缓存，让下一次请求立即重新解析。
        try {
            java.security.Security.setProperty("networkaddress.cache.negative.ttl", "0");
        } catch (Exception ignored) {
            // 安全属性不可写时忽略，不影响启动
        }
        SpringApplication.run(CampusDialogueApplication.class, args);
    }

    /** 启动后：先兜底写入知识库文档，向量库已存在则跳过索引（避免重复向量化与数据库冗余）。 */
    @Bean
    public CommandLineRunner bootstrap(DocumentService documentService,
                                       RetrievalService retrievalService,
                                       VectorStore vectorStore,
                                       UserService userService) {
        return args -> {
            userService.seedIfEmpty();
            documentService.seedIfEmpty();
            if (vectorStore.size() == 0) {
                int n = retrievalService.indexAll();
                System.out.println("[campus-dialogue] 已索引知识库片段：" + n);
            } else {
                System.out.println("[campus-dialogue] 检测到已有向量库（" + vectorStore.size()
                        + " 片段），跳过索引。");
            }
        };
    }
}
