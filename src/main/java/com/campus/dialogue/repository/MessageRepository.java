package com.campus.dialogue.repository;

import com.campus.dialogue.entity.MessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MessageRepository extends JpaRepository<MessageEntity, Long> {

    List<MessageEntity> findBySessionIdOrderByTimestampAsc(long sessionId);

    void deleteBySessionId(long sessionId);

    void deleteBySessionIdAndIdGreaterThanEqual(long sessionId, long id);
}
