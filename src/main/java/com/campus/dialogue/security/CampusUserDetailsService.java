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
    public Mono<UserDetails> findByUsername(String username) {
        return Mono.fromCallable(() -> userRepository.findByUsername(username))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(optional -> optional
                        .map(this::toUserDetails)
                        .map(Mono::just)
                        .orElseGet(() -> Mono.error(new UsernameNotFoundException("用户不存在：" + username))));
    }

    private UserDetails toUserDetails(UserEntity u) {
        return User.withUsername(u.getUsername())
                .password(u.getPasswordHash())
                // roles(role) 会自动补 ROLE_ 前缀；hasRole("ADMIN") 对应 ROLE_ADMIN
                .roles(u.getRole())
                .build();
    }
}
