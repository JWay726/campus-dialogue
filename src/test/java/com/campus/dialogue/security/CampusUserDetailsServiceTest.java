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

    private UserEntity entity(String username, String role) {
        UserEntity u = new UserEntity();
        u.setUsername(username);
        u.setPasswordHash(Passwords.hash("pwd123"));
        u.setRole(role);
        return u;
    }
}
