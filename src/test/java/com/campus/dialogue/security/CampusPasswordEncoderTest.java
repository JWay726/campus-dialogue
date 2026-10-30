package com.campus.dialogue.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** CampusPasswordEncoder 应能校验「历史哈希」与「本类新生成哈希」，并拒绝错误密码。 */
class CampusPasswordEncoderTest {

    private final CampusPasswordEncoder encoder = new CampusPasswordEncoder();

    @Test
    void encodeThenMatches() {
        String encoded = encoder.encode("Test@1234");
        assertNotNull(encoded);
        assertTrue(encoded.contains(":"), "存储格式应为 salt:hash");
        assertTrue(encoder.matches("Test@1234", encoded));
        assertFalse(encoder.matches("wrong-pwd", encoded));
    }

    @Test
    void matchesLegacyHashStoredByPasswords() {
        // 模拟 users 表里已有账号：哈希由 Passwords.hash 生成
        String legacy = Passwords.hash("Test@1234");
        assertTrue(encoder.matches("Test@1234", legacy));
        assertFalse(encoder.matches("admin12", legacy));
    }

    @Test
    void handlesNullAndMalformedInput() {
        assertFalse(encoder.matches(null, "whatever"));
        assertFalse(encoder.matches("x", null));
        assertFalse(encoder.matches("x", "not-a-valid-hash"));
    }

    @Test
    void eachEncodeUsesFreshSalt() {
        assertNotEquals(encoder.encode("same"), encoder.encode("same"));
    }
}
