package com.campus.dialogue.service;

import com.campus.dialogue.config.AppProperties;
import com.campus.dialogue.entity.UserEntity;
import com.campus.dialogue.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** 微信身份相关的账号逻辑：unionid 优先、openid 兜底、占用校验、解绑清理。 */
class UserServiceWechatTest {

    private UserRepository repo;
    private UserService service;

    @BeforeEach
    void setUp() {
        repo = mock(UserRepository.class);
        service = new UserService(repo, new AppProperties());
        when(repo.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void loginPrefersUnionid() {
        UserEntity existing = user(1L, "alice", "openid-A", "union-1");
        when(repo.findByWechatUnionid("union-1")).thenReturn(Optional.of(existing));

        UserEntity got = service.loginOrRegisterByWechat("openid-A", "union-1", "昵称");

        assertSame(existing, got);
        verify(repo, never()).findByWechatOpenid(anyString());
    }

    @Test
    void loginFallsBackToOpenidWhenUnionidMissing() {
        UserEntity existing = user(2L, "bob", "openid-B", null);
        when(repo.findByWechatOpenid("openid-B")).thenReturn(Optional.of(existing));

        UserEntity got = service.loginOrRegisterByWechat("openid-B", null, "昵称");

        assertSame(existing, got);
        verify(repo, never()).findByWechatUnionid(anyString());
    }

    @Test
    void loginRegistersNewAccountWithBothIdentities() {
        when(repo.findByWechatUnionid("union-C")).thenReturn(Optional.empty());
        when(repo.findByWechatOpenid("openid-C")).thenReturn(Optional.empty());
        when(repo.existsByUsername(anyString())).thenReturn(false);

        UserEntity created = service.loginOrRegisterByWechat("openid-C", "union-C", "小明");

        assertEquals("openid-C", created.getWechatOpenid());
        assertEquals("union-C", created.getWechatUnionid());
        assertEquals("USER", created.getRole());
        assertTrue(created.getUsername().startsWith("wx"), created.getUsername());
        assertNotNull(created.getWechatBoundAt());
    }

    @Test
    void bindRejectsWechatAlreadyOwnedByAnotherAccount() {
        UserEntity me = user(1L, "me", null, null);
        UserEntity other = user(2L, "other", "openid-X", null);
        when(repo.findByUsername("me")).thenReturn(Optional.of(me));
        when(repo.findByWechatOpenid("openid-X")).thenReturn(Optional.of(other));
        when(repo.findByWechatUnionid(anyString())).thenReturn(Optional.empty());

        UserService.UserException e = assertThrows(UserService.UserException.class,
                () -> service.bindWechat("me", "openid-X", null, "昵称"));

        assertTrue(e.getMessage().contains("已绑定其他账号"), e.getMessage());
        assertNull(me.getWechatOpenid(), "绑定失败不能改动原数据");
    }

    @Test
    void unbindClearsOpenidAndUnionid() {
        UserEntity me = user(1L, "me", "openid-1", "union-1");
        when(repo.findByUsername("me")).thenReturn(Optional.of(me));

        service.unbindWechat("me");

        assertNull(me.getWechatOpenid());
        assertNull(me.getWechatUnionid());
        assertNull(me.getWechatNickname());
        assertNull(me.getWechatBoundAt());
    }

    private UserEntity user(Long id, String username, String openid, String unionid) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setUsername(username);
        u.setRole("USER");
        u.setWechatOpenid(openid);
        u.setWechatUnionid(unionid);
        return u;
    }
}
