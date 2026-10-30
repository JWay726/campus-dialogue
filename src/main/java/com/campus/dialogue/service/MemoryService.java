package com.campus.dialogue.service;

import com.campus.dialogue.config.AppProperties;
import com.campus.dialogue.model.Message;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** 多轮上下文记忆：从最近消息向前累加，受字符预算（max-chars）约束，避免超出模型上下文。 */
@Service
public class MemoryService {

    private final AppProperties.Memory cfg;

    public MemoryService(AppProperties props) {
        this.cfg = props.getMemory();
    }

    public List<Message> recent(List<Message> all) {
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        int budget = cfg.getMaxChars();
        int window = cfg.getWindowSize();
        int used = 0;
        List<Message> kept = new ArrayList<>();
        // 从最近一条向前累加，直到超出字符预算（至少保留最后一条），且不超过 windowSize 条硬上限
        for (int i = all.size() - 1; i >= 0 && kept.size() < window; i--) {
            Message m = all.get(i);
            int len = m.getContent() == null ? 0 : m.getContent().length();
            if (!kept.isEmpty() && used + len > budget) {
                break;
            }
            kept.add(0, m);
            used += len;
        }
        return kept;
    }
}
