package com.campus.dialogue.service;

import com.campus.dialogue.config.AppProperties;
import com.campus.dialogue.entity.UserEntity;
import com.campus.dialogue.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 用户名与学号共用同一个登录入口，因此两者在全站范围内不能互相冲突：
 * 别人的学号不能拿来做用户名，别人的用户名也不能拿来当学号。
 */
class UserLoginKeyTest {

    private UserRepository repo;
    private UserService service;

    @BeforeEach
    void setUp() {
        repo = mock(UserRepository.class);
        service = new UserService(repo, new AppProperties());
        when(repo.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void registerRejectsUsernameUsedAsStudentNo() {
        UserEntity other = user(2L, "bob", null);
        when(repo.findByUsername("202101")).thenReturn(Optional.empty());
        when(repo.findByStudentNo("202101")).thenReturn(Optional.of(other));

        UserService.UserException e = assertThrows(UserService.UserException.class,
                () -> service.register("202101", "pwd123456"));

        assertTrue(e.getMessage().contains("学号"), e.getMessage());
    }

    @Test
    void registerRejectsDuplicateUsername() {
        when(repo.findByUsername("alice")).thenReturn(Optional.of(user(1L, "alice", null)));

        UserService.UserException e = assertThrows(UserService.UserException.class,
                () -> service.register("alice", "pwd123456"));

        assertTrue(e.getMessage().contains("已被占用"), e.getMessage());
    }

    @Test
    void bindStudentNoRejectsAnotherUsersUsername() {
        UserEntity me = user(1L, "alice", null);
        when(repo.findByUsername("alice")).thenReturn(Optional.of(me));
        when(repo.findByStudentNo("bobby")).thenReturn(Optional.empty());
        // "bobby" 是别人的用户名（学号需 4 位以上，故用 5 位字符串）
        when(repo.findByUsername("bobby")).thenReturn(Optional.of(user(2L, "bobby", null)));

        UserService.UserException e = assertThrows(UserService.UserException.class,
                () -> service.updateStudentNo("alice", "bobby"));

        assertTrue(e.getMessage().contains("用户名"), e.getMessage());
        assertNull(me.getStudentNo(), "绑定失败不能写入脏数据");
    }

    @Test
    void bindStudentNoRejectsTakenStudentNo() {
        UserEntity me = user(1L, "alice", null);
        when(repo.findByUsername("alice")).thenReturn(Optional.of(me));
        when(repo.findByStudentNo("20210101")).thenReturn(Optional.of(user(3L, "carol", "20210101")));

        UserService.UserException e = assertThrows(UserService.UserException.class,
                () -> service.updateStudentNo("alice", "20210101"));

        assertTrue(e.getMessage().contains("已被其他账号绑定"), e.getMessage());
    }

    @Test
    void bindStudentNoSucceedsWhenFree() {
        UserEntity me = user(1L, "alice", null);
        when(repo.findByUsername("alice")).thenReturn(Optional.of(me));
        when(repo.findByStudentNo("2021010203")).thenReturn(Optional.empty());

        assertEquals("2021010203", service.updateStudentNo("alice", "2021010203").studentNo());
        assertEquals("2021010203", me.getStudentNo());
    }

    /** 自己的用户名当学号不产生歧义（同一个字符串仍指向自己），应当允许。 */
    @Test
    void bindOwnUsernameAsStudentNoIsAllowed() {
        UserEntity me = user(1L, "alice", null);
        when(repo.findByUsername("alice")).thenReturn(Optional.of(me));
        when(repo.findByStudentNo("alice")).thenReturn(Optional.empty());

        assertEquals("alice", service.updateStudentNo("alice", "alice").studentNo());
    }

    @Test
    void changeUsernameRejectsTargetUsedAsStudentNo() {
        UserEntity me = user(1L, "alice", null);
        me.setPasswordHash(com.campus.dialogue.security.Passwords.hash("pwd123456"));
        when(repo.findByUsername("alice")).thenReturn(Optional.of(me));
        when(repo.findByUsername("20210103")).thenReturn(Optional.empty());
        when(repo.findByStudentNo("20210103")).thenReturn(Optional.of(user(4L, "dave", "20210103")));

        UserService.UserException e = assertThrows(UserService.UserException.class,
                () -> service.changeUsername("alice", "20210103", "pwd123456"));

        assertTrue(e.getMessage().contains("学号"), e.getMessage());
        assertEquals("alice", me.getUsername(), "改名失败不能改动原用户名");
    }

    private UserEntity user(Long id, String username, String studentNo) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setUsername(username);
        u.setRole("USER");
        u.setStudentNo(studentNo);
        return u;
    }
}
