package com.campus.dialogue.security;

import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Spring Security 的 {@link PasswordEncoder} 实现，算法沿用项目既有的
 * 「SHA-256 + 每密码独立随机盐」（存储格式 {@code saltB64:hashB64}，见 {@link Passwords}）。
 *
 * <p>为什么不用 {@code BCryptPasswordEncoder}：users 表里已有历史账号的哈希是按本算法存的，
 * 直接换算法会让所有存量账号无法登录。实现 Spring Security 的 {@code PasswordEncoder} 接口后，
 * {@code DaoAuthenticationProvider}（reactive 版即 {@code UserDetailsRepositoryReactiveAuthenticationManager}）
 * 会通过本类校验密码，业务代码不需要感知算法细节。
 *
 * <p>新项目建议直接用 {@code BCryptPasswordEncoder}（自带盐、可调强度、抗暴力破解更强）；
 * 若要平滑升级，可让 {@link #upgradeEncoding(String)} 返回 true，登录成功后按新算法重写哈希。
 */
public class CampusPasswordEncoder implements PasswordEncoder {

    @Override
    public String encode(CharSequence rawPassword) {
        return Passwords.hash(rawPassword == null ? "" : rawPassword.toString());
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (rawPassword == null) {
            return false;
        }
        return Passwords.matches(rawPassword.toString(), encodedPassword);
    }
}
