package com.campus.dialogue.service;

import com.campus.dialogue.config.AppProperties;
import com.campus.dialogue.model.Message;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemoryServiceTest {

    @Test
    void trimsByCharBudgetFromMostRecent() {
        AppProperties props = new AppProperties();
        props.getMemory().setMaxChars(20);  // 每条 "abcde" 占 5 字，预算 20 可容纳 4 条
        props.getMemory().setWindowSize(10);
        MemoryService svc = new MemoryService(props);

        List<Message> ms = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            ms.add(new Message(i, "user", "abcde"));
        }
        List<Message> out = svc.recent(ms);
        assertEquals(4, out.size());
        assertEquals(2L, out.get(0).getId());   // 保留 m2..m5，截断了最早的 m1
        assertEquals(5L, out.get(out.size() - 1).getId());
    }

    @Test
    void keepsAllWhenWithinBudget() {
        AppProperties props = new AppProperties();
        props.getMemory().setMaxChars(100);
        props.getMemory().setWindowSize(10);
        MemoryService svc = new MemoryService(props);

        List<Message> ms = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            ms.add(new Message(i, "user", "abcde"));
        }
        List<Message> out = svc.recent(ms);
        assertEquals(3, out.size());
    }
}
