package com.campus.dialogue.repository;

import com.campus.dialogue.entity.SessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SessionRepository extends JpaRepository<SessionEntity, Long> {

    /** 当前用户的所有会话，按最近更新倒序。 */
    List<SessionEntity> findByUsernameOrderByUpdatedAtDesc(String username);

    /** 校验会话归属：仅当用户名匹配时才返回，杜绝跨用户访问。 */
    Optional<SessionEntity> findByIdAndUsername(Long id, String username);

    /**
     * 用户名变更后把历史会话一并改归属，避免改名后自己的对话记录"消失"。
     * 返回受影响行数（会话数）。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update SessionEntity s set s.username = :newName where s.username = :oldName")
    int reassignUsername(@Param("oldName") String oldName, @Param("newName") String newName);
}
