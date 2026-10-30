package com.campus.dialogue.repository;

import com.campus.dialogue.entity.SessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SessionRepository extends JpaRepository<SessionEntity, Long> {

    /** 当前用户的所有会话，按最近更新倒序。 */
    List<SessionEntity> findByUsernameOrderByUpdatedAtDesc(String username);

    /** 校验会话归属：仅当用户名匹配时才返回，杜绝跨用户访问。 */
    Optional<SessionEntity> findByIdAndUsername(Long id, String username);
}
