package com.campus.dialogue.service;

import com.campus.dialogue.entity.MessageEntity;
import com.campus.dialogue.entity.SessionEntity;
import com.campus.dialogue.model.Message;
import com.campus.dialogue.model.Session;
import com.campus.dialogue.repository.MessageRepository;
import com.campus.dialogue.repository.SessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** 会话管理：基于 Spring Data JPA 持久化到 MySQL。会话按登录用户隔离。 */
@Service
public class SessionService {

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;

    public SessionService(SessionRepository sessionRepository, MessageRepository messageRepository) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
    }

    public Session createSession(String username) {
        long now = Instant.now().toEpochMilli();
        SessionEntity saved = sessionRepository.save(new SessionEntity(username, "新对话", now, now));
        return toModel(saved, new ArrayList<>());
    }

    /**
     * 取已有会话（必须归属该用户），不存在或不属于该用户则返回 null。
     */
    public Session get(Long sessionId, String username) {
        return sessionRepository.findByIdAndUsername(sessionId, username)
                .map(e -> toModel(e, loadMessages(sessionId)))
                .orElse(null);
    }

    /**
     * 取已有会话；若 id 为空、不存在或不归属该用户，则新建一个归属该用户的会话。
     * 这样即便前端传了一个别人的 sessionId，也不会越权读写，只会落到自己的新会话。
     */
    public Session getOrCreate(Long sessionId, String username) {
        if (sessionId != null) {
            Session s = get(sessionId, username);
            if (s != null) {
                return s;
            }
        }
        return createSession(username);
    }

    /** 仅返回当前用户自己的会话（按最近更新倒序）。 */
    public List<Session> listSessions(String username) {
        return sessionRepository.findByUsernameOrderByUpdatedAtDesc(username).stream()
                .map(e -> toModel(e, new ArrayList<>()))
                .collect(Collectors.toList());
    }

    /**
     * 删除会话及其全部消息（仅当会话归属该用户）。
     * 必须加 @Transactional：messageRepository.deleteBySessionId 是派生删除查询，
     * 没有事务会抛 TransactionRequiredException（表现为接口 500、会话删不掉）。
     */
    @Transactional
    public void delete(Long sessionId, String username) {
        sessionRepository.findByIdAndUsername(sessionId, username).ifPresent(se -> {
            messageRepository.deleteBySessionId(sessionId);
            sessionRepository.deleteById(sessionId);
        });
    }

    /**
     * 删除某条消息及其之后的所有消息（用于“修改历史消息并重新提问”）。
     * 仅当会话归属该用户时执行；消息 id 自增，id >= fromMsgId 即该消息及其之后。
     */
    @Transactional
    public void deleteMessagesFrom(long sessionId, String username, long fromMsgId) {
        if (sessionRepository.findByIdAndUsername(sessionId, username).isEmpty()) {
            return;
        }
        messageRepository.deleteBySessionIdAndIdGreaterThanEqual(sessionId, fromMsgId);
        sessionRepository.findById(sessionId).ifPresent(se -> {
            se.setUpdatedAt(Instant.now().toEpochMilli());
            sessionRepository.save(se);
        });
    }

    @Transactional
    public void rename(Long sessionId, String username, String title) {
        SessionEntity se = sessionRepository.findByIdAndUsername(sessionId, username).orElse(null);
        if (se == null) {
            return;
        }
        String t = (title == null ? "" : title.trim());
        if (t.isEmpty()) t = "新对话";
        if (t.length() > 50) t = t.substring(0, 50);
        se.setTitle(t);
        se.setUpdatedAt(Instant.now().toEpochMilli());
        sessionRepository.save(se);
    }

    @Transactional
    public synchronized void appendMessage(long sessionId, String role, String content) {
        SessionEntity se = sessionRepository.findById(sessionId).orElse(null);
        if (se == null) {
            return;
        }
        long now = Instant.now().toEpochMilli();
        messageRepository.save(new MessageEntity(sessionId, role, content, now));
        if ("user".equals(role) && "新对话".equals(se.getTitle())) {
            se.setTitle(content.length() > 20 ? content.substring(0, 20) + "…" : content);
        }
        se.setUpdatedAt(now);
        sessionRepository.save(se);
    }

    /** 返回会话的历史消息（用于组装 prompt）。调用方需保证 sessionId 已归属当前用户。 */
    public List<Message> history(long sessionId) {
        return loadMessages(sessionId);
    }

    /**
     * 账号改名后同步会话归属：把 oldUsername 名下的会话全部改到 newUsername 名下，
     * 否则改名用户登录后会看不到自己原来的对话（会话按 username 隔离）。
     *
     * @return 受影响的会话数
     */
    @Transactional
    public int renameOwner(String oldUsername, String newUsername) {
        if (oldUsername == null || newUsername == null || oldUsername.equals(newUsername)) {
            return 0;
        }
        return sessionRepository.reassignUsername(oldUsername, newUsername);
    }

    /**
     * 删除某账号名下的全部会话与消息（删账号时调用）。
     * 不清理会留下孤儿会话：以后有人注册/改成同名，就会"继承"这些历史对话。
     *
     * @return 删除的会话数
     */
    @Transactional
    public int deleteAllOf(String username) {
        if (username == null) return 0;
        List<SessionEntity> list = sessionRepository.findByUsernameOrderByUpdatedAtDesc(username);
        for (SessionEntity se : list) {
            messageRepository.deleteBySessionId(se.getId());
            sessionRepository.deleteById(se.getId());
        }
        return list.size();
    }

    private List<Message> loadMessages(long sessionId) {
        return messageRepository.findBySessionIdOrderByTimestampAsc(sessionId).stream()
                .map(e -> new Message(e.getId(), e.getRole(), e.getContent(), e.getTimestamp()))
                .collect(Collectors.toList());
    }

    private Session toModel(SessionEntity e, List<Message> messages) {
        return new Session(e.getId(), e.getTitle(), e.getCreatedAt(), e.getUpdatedAt(), messages);
    }
}
