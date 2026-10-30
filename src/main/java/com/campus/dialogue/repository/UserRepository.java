package com.campus.dialogue.repository;

import com.campus.dialogue.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<UserEntity, Long> {
    Optional<UserEntity> findByUsername(String username);
    boolean existsByUsername(String username);
    Optional<UserEntity> findByStudentNo(String studentNo);
    Optional<UserEntity> findByWechatOpenid(String wechatOpenid);
    Optional<UserEntity> findByWechatUnionid(String wechatUnionid);
}
