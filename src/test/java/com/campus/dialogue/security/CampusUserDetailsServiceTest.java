package com.campus.dialogue.security;

import com.campus.dialogue.entity.UserEntity;
import com.campus.dialogue.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** CampusUserDetailsService 应把 users 表记录转成带 ROLE_ 权限的 UserDetails。 */
class CampusUserDetailsServiceTest {

    private final UserRepository repo = mock(UserRepository.class);
    private final CampusUserDetailsService service = new CampusUserDetailsService(repo);

    @Test
    void loadsUserWithRolePrefix() {
        when(repo.findByUsername("admin")).thenReturn(Optional.of(entity("admin", "ADMIN")));

        UserDetails u = service.findByUsername("admin").block();

        assertNotNull(u);
        assertEquals("admin", u.getUsername());
        assertTrue(u.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_ADMIN"::equals));
    }

    @Test
    void normalUserGetsRoleUser() {
        when(repo.findByUsername("bob")).thenReturn(Optional.of(entity("bob", "USER")));

        UserDetails u = service.findByUsername("bob").block();

        assertNotNull(u);
        assertTrue(u.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_USER"::equals));
    }

    @Test
    void missingUserThrows() {
        when(repo.findByUsername("ghost")).thenReturn(Optional.empty());
        assertThrows(UsernameNotFoundException.class, () -> service.findByUsername("ghost").block());
    }

    /** 学号也能登录，但登录后的 principal 必须是真实用户名（会话、授权都依赖它）。 */
    @Test
    void logsInByStudentNoButKeepsRealUsername() {
        UserEntity u = entity("alice", "USER");
        u.setStudentNo("2021010101");
        when(repo.findByUsername("2021010101")).thenReturn(Optional.empty());
        when(repo.findByStudentNo("2021010101")).thenReturn(Optional.of(u));

        UserDetails details = service.findByUsername("2021010101").block();

        assertNotNull(details);
        assertEquals("alice", details.getUsername(), "principal 必须是用户名，不能是学号");
        assertTrue(details.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_USER"::equals));
    }

    /** 用户名优先：某串字符既是自己的用户名、又是别人的学号时，登进自己。 */
    @Test
    void usernameWinsOverAnotherUsersStudentNo() {
        when(repo.findByUsername("2021010101")).thenReturn(Optional.of(entity("2021010101", "ADMIN")));

        UserDetails details = service.findByUsername("2021010101").block();

        assertNotNull(details);
        assertEquals("2021010101", details.getUsername());
        assertTrue(details.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_ADMIN"::equals));
    }

    /** 输入含首尾空格（手机键盘常见）也要能登录。 */
    @Test
    void trimsLoginInput() {
        when(repo.findByUsername("bob")).thenReturn(Optional.of(entity("bob", "USER")));

        UserDetails details = service.findByUsername("  bob  ").block();

        assertNotNull(details);
        assertEquals("bob", details.getUsername());
    }

    private UserEntity entity(String username, String role) {
        UserEntity u = new UserEntity();
        u.setUsername(username);
        u.setPasswordHash(Passwords.hash("pwd123"));
        u.setRole(role);
        return u;
    }
}
