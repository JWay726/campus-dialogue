package com.campus.dialogue.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 密码哈希工具：SHA-256 + 随机盐（每个密码独立 16 字节盐），存储为 "saltB64:hashB64"。
 * 校验使用恒定时间比较，避免时序侧信道。
 *
 * <p>对外由 {@link CampusPasswordEncoder} 适配成 Spring Security 的 {@code PasswordEncoder}，
 * 认证时由框架调用，业务代码不直接使用本类（注册/改密仍用 {@link #hash(String)}）。
 */
public final class Passwords {

    private static final SecureRandom RNG = new SecureRandom();
    private static final int SALT_BYTES = 16;

    private Passwords() {}

    /** 对明文密码生成 "salt:hash" 形式的存储串。 */
    public static String hash(String raw) {
        byte[] salt = new byte[SALT_BYTES];
        RNG.nextBytes(salt);
        byte[] digest = sha256(salt, raw.getBytes(StandardCharsets.UTF_8));
        return b64(salt) + ":" + b64(digest);
    }

    /** 校验明文与存储串是否匹配。 */
    public static boolean matches(String raw, String stored) {
        if (raw == null || stored == null) return false;
        String[] parts = stored.split(":", 2);
        if (parts.length != 2) return false;
        byte[] salt;
        try {
            salt = b64d(parts[0]);
        } catch (Exception e) {
            return false;
        }
        byte[] digest = sha256(salt, raw.getBytes(StandardCharsets.UTF_8));
        return constantTimeEquals(b64(digest), parts[1]);
    }

    private static byte[] sha256(byte[] salt, byte[] raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);
            md.update(raw);
            return md.digest();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static byte[] b64d(String s) {
        return Base64.getUrlDecoder().decode(s);
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] ab = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        if (ab.length != bb.length) return false;
        int r = 0;
        for (int i = 0; i < ab.length; i++) r |= ab[i] ^ bb[i];
        return r == 0;
    }
}
