package com.campus.dialogue.service;

import com.campus.dialogue.entity.VectorEntity;
import com.campus.dialogue.model.DocChunk;
import com.campus.dialogue.repository.VectorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VectorStoreTest {

    @Mock
    VectorRepository repo;

    final List<VectorEntity> store = new ArrayList<>();
    VectorStore vs;

    @BeforeEach
    void setup() {
        store.clear();
        when(repo.save(any(VectorEntity.class))).thenAnswer(inv -> {
            store.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        doAnswer(inv -> {
            String id = inv.getArgument(0);
            store.removeIf(e -> id.equals(e.getDocId()));
            return null;
        }).when(repo).deleteByDocId(anyString());
        vs = new VectorStore(repo);
    }

    @Test
    void searchOrdersByScoreAndRemoveByDocId() {
        float[] v = {1f, 0f, 0f};
        vs.add(new DocChunk("a", "d1", "hi", v));
        vs.add(new DocChunk("b", "d1", "hello", v));
        vs.add(new DocChunk("c", "d2", "bye", new float[]{0f, 1f, 0f}));

        var hits = vs.searchWithScores(new float[]{1f, 0f, 0f}, 10);
        assertEquals("hi", hits.get(0).chunk().text());
        assertTrue(hits.get(0).score() > hits.get(hits.size() - 1).score());

        vs.removeByDocId("d1");
        assertEquals(1, vs.size());
        assertEquals("d2", vs.searchWithScores(new float[]{0f, 1f, 0f}, 10).get(0).chunk().docId());
    }

    @Test
    void addPersistsDocId() {
        vs.add(new DocChunk("a", "docX", "text", new float[]{1f}));
        ArgumentCaptor<VectorEntity> cap = ArgumentCaptor.forClass(VectorEntity.class);
        verify(repo).save(cap.capture());
        assertEquals("docX", cap.getValue().getDocId());
    }
}
