package com.campus.dialogue.security;

import com.campus.dialogue.entity.UserEntity;
import com.campus.dialogue.repository.UserRepository;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Spring Security 标准的用户加载入口：{@link ReactiveUserDetailsService}。
 *
 * <p>WebFlux 下的认证链路：
 * <pre>
 * 表单登录 POST /api/auth/login
 *   → ServerFormLoginAuthenticationConverter 取出 username/password
 *   → UserDetailsRepositoryReactiveAuthenticationManager（即 DaoAuthenticationProvider）
 *   → 本类 loadUserByUsername() 查 users 表
 *   → CampusPasswordEncoder.matches() 校验密码
 *   → 认证成功，生成 Authentication 并写入 WebSession
 * </pre>
 *
 * <p><b>登录标识可以是用户名或学号</b>：先按 username 精确匹配，找不到再按 users.student_no 匹配。
 * 匹配到后，{@link UserDetails#getUsername()} 一律返回<b>真实用户名</b>而非用户输入的学号，
 * 这样上层的授权、会话归属（sessions.username）、@AuthenticationPrincipal 取到的都是用户名，
 * 学号只作为登录入口存在，不会污染业务数据。
 *
 * <p>角色以 {@code ROLE_} 前缀存入权限（数据库里是 {@code ADMIN}/{@code USER}），
 * 因此 {@code SecurityConfig} 里可以直接用 {@code hasRole("ADMIN")}。
 * 查库是阻塞操作，统一放到 {@link Schedulers#boundedElastic()} 执行，避免阻塞 Netty 事件循环。
 */
@Service
public class CampusUserDetailsService implements ReactiveUserDetailsService {

    private final UserRepository userRepository;

    public CampusUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public Mono<UserDetails> findByUsername(String login) {
        return Mono.fromCallable(() -> resolve(login))
                .subscribeOn(Schedulers.boundedElastic())
                .map(this::toUserDetails);
    }

    /**
     * 用户名优先、学号兜底：两者共用一个登录入口。
     * 用户名优先保证「用户名恰好等于别人学号」时，本人仍能用用户名登录自己的账号
     * （这种歧义已在注册/绑定环节拦住，见 UserService 的登录键唯一校验）。
     */
    private UserEntity resolve(String login) {
        String key = login == null ? "" : login.trim();
        if (key.isEmpty()) {
            throw new UsernameNotFoundException("用户不存在");
        }
        return userRepository.findByUsername(key)
                .or(() -> userRepository.findByStudentNo(key))
                .orElseThrow(() -> new UsernameNotFoundException("用户不存在：" + key));
    }

    private UserDetails toUserDetails(UserEntity u) {
        return User.withUsername(u.getUsername())
                .password(u.getPasswordHash())
                // roles(role) 会自动补 ROLE_ 前缀；hasRole("ADMIN") 对应 ROLE_ADMIN
                .roles(u.getRole())
                .build();
    }
}
