package com.campus.dialogue.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 微信错误码翻译与 state 生成：都是纯函数，不依赖网络。 */
class WechatServiceTest {

    @Test
    void friendlyErrorTranslatesKnownCodes() {
        assertTrue(WechatService.friendlyError(40029, "invalid code").contains("无效或已过期"));
        assertTrue(WechatService.friendlyError("40163", "code been used").contains("已被使用"));
        assertTrue(WechatService.friendlyError(40013, "invalid appid").contains("AppID"));
        assertTrue(WechatService.friendlyError(40125, "invalid appsecret").contains("AppSecret"));
        assertTrue(WechatService.friendlyError(41008, "missing code").contains("缺少"));
        assertTrue(WechatService.friendlyError(40222, "freq limit").contains("频繁"));
    }

    @Test
    void friendlyErrorKeepsUnknownCodeAndMessage() {
        String msg = WechatService.friendlyError(99999, "something odd");
        assertTrue(msg.contains("99999"), msg);
        assertTrue(msg.contains("something odd"), msg);
    }

    @Test
    void newStateIsRandom32Hex() {
        String a = WechatService.newState();
        String b = WechatService.newState();
        assertEquals(32, a.length());
        assertTrue(a.matches("[0-9a-f]{32}"), a);
        assertNotEquals(a, b, "两次生成必须不同，否则 state 就失去防 CSRF 的意义");
    }
}
