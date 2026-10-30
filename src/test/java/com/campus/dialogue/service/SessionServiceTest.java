package com.campus.dialogue.service;

import com.campus.dialogue.entity.SessionEntity;
import com.campus.dialogue.repository.MessageRepository;
import com.campus.dialogue.repository.SessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.reset;

@ExtendWith(MockitoExtension.class)
class SessionServiceTest {

    @Mock SessionRepository sessionRepository;
    @Mock MessageRepository messageRepository;
    @InjectMocks SessionService sessionService;

    /** 构造带 id 的 SessionEntity（id 字段无 setter，用反射注入）。 */
    private SessionEntity entity(long id, String username) {
        SessionEntity e = new SessionEntity(username, "t", 1L, 1L);
        try {
            Field f = SessionEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(e, id);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return e;
    }

    @Test
    void createSessionTagsOwner() {
        when(sessionRepository.save(any())).thenAnswer(inv -> entity(99L, "alice"));
        SessionEntity captured = new SessionEntity("alice", "新对话", 0, 0);
        sessionService.createSession("alice");
        ArgumentCaptor<SessionEntity> cap = ArgumentCaptor.forClass(SessionEntity.class);
        verify(sessionRepository).save(cap.capture());
        assertEquals("alice", cap.getValue().getUsername());
    }

    @Test
    void listSessionsReturnsOnlyOwned() {
        when(sessionRepository.findByUsernameOrderByUpdatedAtDesc("alice"))
                .thenReturn(List.of(entity(1L, "alice"), entity(2L, "alice")));
        var list = sessionService.listSessions("alice");
        assertEquals(2, list.size());
        assertTrue(list.stream().allMatch(s -> s.getId() == 1L || s.getId() == 2L));
    }

    @Test
    void getReturnsNullWhenNotOwned() {
        when(sessionRepository.findByIdAndUsername(10L, "alice")).thenReturn(Optional.of(entity(10L, "alice")));
        when(sessionRepository.findByIdAndUsername(11L, "alice")).thenReturn(Optional.empty());

        assertNotNull(sessionService.get(10L, "alice"));
        assertNull(sessionService.get(11L, "alice"), "别的用户/不存在的会话应返回 null，避免越权读取");
    }

    @Test
    void deleteOnlyWhenOwned() {
        when(sessionRepository.findByIdAndUsername(10L, "alice")).thenReturn(Optional.of(entity(10L, "alice")));
        sessionService.delete(10L, "alice");
        verify(messageRepository).deleteBySessionId(10L);
        verify(sessionRepository).deleteById(10L);

        // 重置后单独验证“非归属用户”不应触发删除
        reset(sessionRepository, messageRepository);
        when(sessionRepository.findByIdAndUsername(10L, "bob")).thenReturn(Optional.empty());
        sessionService.delete(10L, "bob");
        verify(messageRepository, never()).deleteBySessionId(anyLong());
        verify(sessionRepository, never()).deleteById(anyLong());
    }

    @Test
    void deleteMessagesFromOnlyWhenOwned() {
        when(sessionRepository.findByIdAndUsername(10L, "alice")).thenReturn(Optional.of(entity(10L, "alice")));
        when(sessionRepository.findById(10L)).thenReturn(Optional.of(entity(10L, "alice")));
        sessionService.deleteMessagesFrom(10L, "alice", 5L);
        verify(messageRepository).deleteBySessionIdAndIdGreaterThanEqual(10L, 5L);

        // 重置后单独验证“非归属用户”不应触发删除
        reset(sessionRepository, messageRepository);
        when(sessionRepository.findByIdAndUsername(10L, "bob")).thenReturn(Optional.empty());
        sessionService.deleteMessagesFrom(10L, "bob", 5L);
        verify(messageRepository, never()).deleteBySessionIdAndIdGreaterThanEqual(anyLong(), anyLong());
    }

    @Test
    void renameOnlyWhenOwned() {
        when(sessionRepository.findByIdAndUsername(10L, "alice")).thenReturn(Optional.of(entity(10L, "alice")));
        when(sessionRepository.findByIdAndUsername(10L, "bob")).thenReturn(Optional.empty());

        sessionService.rename(10L, "alice", "新标题");
        ArgumentCaptor<SessionEntity> cap = ArgumentCaptor.forClass(SessionEntity.class);
        verify(sessionRepository).save(cap.capture());
        assertEquals("新标题", cap.getValue().getTitle());

        sessionService.rename(10L, "bob", "黑客标题");
        verify(sessionRepository, times(1)).save(any()); // 仅上面一次，bob 的调用应被忽略
    }
}
