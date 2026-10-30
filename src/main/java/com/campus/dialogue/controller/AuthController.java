package com.campus.dialogue.controller;

import com.campus.dialogue.entity.UserEntity;
import com.campus.dialogue.service.UserService;
import com.campus.dialogue.service.WechatService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 账号相关接口。
 *
 * <p><b>登录 / 登出已不再由本类实现</b>——它们由 Spring Security 的
 * {@code FormLoginWebFilter}（{@code POST /api/auth/login}）和 {@code LogoutWebFilter}
 * （{@code POST /api/auth/logout}）处理，见 {@code SecurityConfig}。
 *
 * <p>本类只保留：
 * <ul>
 *   <li>{@code GET  /api/auth/me}：当前登录用户（需登录，供前端判断登录态与角色）；</li>
 *   <li>{@code POST /api/auth/register}：开放注册（公开）；</li>
 *   <li>微信扫码登录：没有密码，认证成功后手动把 SecurityContext 写入 Session，
 *       效果与表单登录一致。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** 微信登录 state 在 WebSession 中的键；回调时取出比对后立即删除（一次性）。 */
    private static final String STATE_KEY_LOGIN = "campus.wechat.login.state";

    private final UserService userService;
    private final WechatService wechatService;
    private final ServerSecurityContextRepository securityContextRepository;

    public AuthController(UserService userService, WechatService wechatService,
                          ServerSecurityContextRepository securityContextRepository) {
        this.userService = userService;
        this.wechatService = wechatService;
        this.securityContextRepository = securityContextRepository;
    }

    /** 当前登录用户：直接从 SecurityContext 的 principal（UserDetails）读取，未登录由安全链返回 401。 */
    @GetMapping("/me")
    public Mono<ResponseEntity<Map<String, Object>>> me(@AuthenticationPrincipal UserDetails me) {
        if (me == null) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.<String, Object>of("error", "unauthorized")));
        }
        return Mono.just(ResponseEntity.ok(Map.<String, Object>of(
                "username", me.getUsername(),
                "role", roleOf(me.getAuthorities()))));
    }

    /** 开放注册：默认 USER 角色；成功后返回用户名与角色（不自动登录，引导去登录页）。 */
    @PostMapping("/register")
    public Mono<ResponseEntity<Map<String, Object>>> register(@RequestBody Map<String, String> body) {
        return Mono.fromCallable(() -> {
            try {
                UserEntity u = userService.register(body.get("username"), body.get("password"));
                return ResponseEntity.ok(Map.<String, Object>of(
                        "username", u.getUsername(), "role", u.getRole()));
            } catch (UserService.UserException e) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.<String, Object>of("error", e.getMessage()));
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 微信扫码登录：先校验 state（防 CSRF），再用授权 code 换 openid/unionid，
     * 已绑定账号则登录、否则自动注册 USER 并绑定微信。
     * 认证成功后把 SecurityContext 写入 WebSession（与表单登录等价，浏览器拿到 SESSION Cookie）。
     */
    @PostMapping("/wechat/login")
    public Mono<ResponseEntity<Map<String, Object>>> wechatLogin(@RequestBody(required = false) Map<String, String> body,
                                                                ServerWebExchange exchange) {
        String code = body == null ? null : body.get("code");
        String state = body == null ? null : body.get("state");
        return consumeState(exchange, state).flatMap(ok -> {
            if (!ok) {
                return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.<String, Object>of("error", "授权状态校验失败（state 不匹配或已过期），请重新扫码")));
            }
            return wechatService.exchangeCode(code)
                    .flatMap(wu -> Mono.fromCallable(() ->
                                    userService.loginOrRegisterByWechat(wu.openid(), wu.unionid(), wu.nickname()))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(user -> loginIntoSession(exchange, user)
                                    .thenReturn(ResponseEntity.ok(loginResult(user.getUsername(),
                                            user.getRole(), wu.demo())))))
                    .onErrorResume(e -> Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .body(Map.<String, Object>of("error", orDefault(e.getMessage())))));
        });
    }

    /**
     * 微信扫码登录用的授权地址。
     * 已配置凭据：生成一次性 state 存进当前 WebSession，随 url 一起返回（前端只负责跳转）。
     * 未配置凭据：返回 demo=true，前端走演示登录。
     */
    @GetMapping("/wechat/authorize-url")
    public Mono<ResponseEntity<Map<String, Object>>> wechatLoginAuthorizeUrl(ServerWebExchange exchange) {
        if (!wechatService.isConfigured()) {
            return Mono.just(ResponseEntity.ok(authorizeView(null, true)));
        }
        String state = WechatService.newState();
        return exchange.getSession()
                .doOnNext(s -> s.getAttributes().put(STATE_KEY_LOGIN, state))
                .map(s -> ResponseEntity.ok(authorizeView(wechatService.authorizeUrl(state, true), false)));
    }

    // ---------------- 内部工具 ----------------

    /** 取出并删除会话中的 state，与回调带回的值比对；演示模式（未配置凭据）不校验。 */
    private Mono<Boolean> consumeState(ServerWebExchange exchange, String state) {
        if (!wechatService.isConfigured()) {
            return Mono.just(true);
        }
        if (state == null || state.isBlank()) {
            return Mono.just(false);
        }
        return exchange.getSession().map(s -> {
            Object expected = s.getAttributes().remove(STATE_KEY_LOGIN);
            return expected != null && expected.equals(state);
        });
    }

    private Map<String, Object> authorizeView(String url, boolean demo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("demo", demo);
        m.put("url", url);
        m.put("configured", !demo);
        return m;
    }

    /** 把认证结果写入 WebSession（框架在响应提交时下发 SESSION Cookie）。 */
    private Mono<Void> loginIntoSession(ServerWebExchange exchange, UserEntity user) {
        // 与表单登录保持一致：principal 必须是 UserDetails，否则 @AuthenticationPrincipal 取不到用户名
        UserDetails principal = User.withUsername(user.getUsername())
                .password(user.getPasswordHash())
                .roles(user.getRole())
                .build();
        Authentication authentication = UsernamePasswordAuthenticationToken
                .authenticated(principal, null, principal.getAuthorities());
        SecurityContext context = new SecurityContextImpl(authentication);
        return securityContextRepository.save(exchange, context);
    }

    private Map<String, Object> loginResult(String username, String role, boolean demo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", username);
        m.put("role", role);
        m.put("demo", demo);
        return m;
    }

    private String roleOf(Collection<? extends GrantedAuthority> authorities) {
        return authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length()))
                .findFirst().orElse("USER");
    }

    private String orDefault(String msg) {
        return msg == null || msg.isBlank() ? "请求失败" : msg;
    }
}
