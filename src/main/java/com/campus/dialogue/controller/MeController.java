package com.campus.dialogue.controller;

import com.campus.dialogue.dto.ProfileDto;
import com.campus.dialogue.service.UserService;
import com.campus.dialogue.service.WechatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 个人中心接口（需登录）：账号资料、学号绑定、微信绑定、修改密码。
 *
 * <p>当前用户由 Spring Security 注入（{@code @AuthenticationPrincipal}）；
 * {@code /api/me/**} 已在 {@code SecurityConfig} 中声明为 authenticated，
 * 未登录请求到不了控制器。
 *
 * <p>所有响应体统一为「资料字段 + wechatConfigured」，前端据此决定微信绑定走真实扫码还是演示模式。
 */
@RestController
@RequestMapping("/api/me")
public class MeController {

    private static final Logger log = LoggerFactory.getLogger(MeController.class);

    /** 微信绑定 state 在 WebSession 中的键（与登录用不同键，避免两个流程互相覆盖）。 */
    private static final String STATE_KEY_BIND = "campus.wechat.bind.state";

    private final UserService userService;
    private final WechatService wechatService;
    private final ServerSecurityContextRepository securityContextRepository;

    public MeController(UserService userService, WechatService wechatService,
                        ServerSecurityContextRepository securityContextRepository) {
        this.userService = userService;
        this.wechatService = wechatService;
        this.securityContextRepository = securityContextRepository;
    }

    /** 当前账号资料。 */
    @GetMapping
    public Mono<ResponseEntity<Object>> profile(@AuthenticationPrincipal(expression = "username") String me) {
        return handle(me, name -> ResponseEntity.ok(profileView(userService.profileOf(name))));
    }

    /** 绑定/修改学号；studentNo 传空串表示解绑。 */
    @PutMapping("/student-no")
    public Mono<ResponseEntity<Object>> updateStudentNo(@RequestBody(required = false) Map<String, String> body,
                                                        @AuthenticationPrincipal(expression = "username") String me) {
        String studentNo = body == null ? null : body.get("studentNo");
        return handle(me, name -> ResponseEntity.ok(profileView(userService.updateStudentNo(name, studentNo))));
    }

    /** 修改密码：需校验原密码。 */
    @PostMapping("/password")
    public Mono<ResponseEntity<Object>> changePassword(@RequestBody(required = false) Map<String, String> body,
                                                       @AuthenticationPrincipal(expression = "username") String me) {
        String oldPwd = body == null ? null : body.get("oldPassword");
        String newPwd = body == null ? null : body.get("newPassword");
        return handle(me, name -> {
            userService.changePassword(name, oldPwd, newPwd);
            return ResponseEntity.ok(Map.of("message", "密码已修改，请使用新密码重新登录"));
        });
    }

    /**
     * 修改用户名（登录账号）：需校验当前密码。
     * 改完会同步刷新会话中的登录身份，避免后续请求还按旧用户名处理；
     * 历史会话归属与管理员端用户列表都会同步（同一张 users 表 + sessions 归属迁移）。
     */
    @PostMapping("/username")
    public Mono<ResponseEntity<Object>> changeUsername(@RequestBody(required = false) Map<String, String> body,
                                                       @AuthenticationPrincipal(expression = "username") String me,
                                                       ServerWebExchange exchange) {
        String newName = body == null ? null : body.get("newUsername");
        String password = body == null ? null : body.get("password");
        if (me == null) {
            return Mono.just(unauthorized());
        }
        return Mono.fromCallable(() -> userService.changeUsername(me, newName, password))
                .subscribeOn(Schedulers.boundedElastic())
                // 回到请求线程再做会话写入：WebSession 的读写要和原请求同一上下文，
                // 否则新会话写不回去，客户端下一次请求就成匿名（表现为改名后被踢下线）。
                .flatMap(p -> refreshPrincipal(exchange, p).map(ok -> ok
                        ? ResponseEntity.ok((Object) profileView(p))
                        : ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                .body((Object) Map.of("error", "用户名已修改，请重新登录"))))
                .onErrorResume(UserService.UserException.class, e -> Mono.just(badRequest(e.getMessage())))
                .onErrorResume(e -> Mono.just(serverError(e.getMessage())));
    }

    /** 微信授权地址：已配置凭据时生成一次性 state 存入会话；未配置时返回 demo=true（前端走演示绑定）。 */
    @GetMapping("/wechat/authorize-url")
    public Mono<ResponseEntity<Object>> wechatAuthorizeUrl(@AuthenticationPrincipal(expression = "username") String me,
                                                           ServerWebExchange exchange) {
        if (me == null) {
            return Mono.just(unauthorized());
        }
        if (!wechatService.isConfigured()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("demo", true);
            m.put("url", null);
            m.put("configured", false);
            return Mono.just(ResponseEntity.ok((Object) m));
        }
        String state = WechatService.newState();
        return exchange.getSession()
                .doOnNext(s -> s.getAttributes().put(STATE_KEY_BIND, state))
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("demo", false);
                    m.put("url", wechatService.authorizeUrl(state));
                    m.put("configured", true);
                    return ResponseEntity.ok((Object) m);
                });
    }

    /** 微信绑定：先校验 state（防 CSRF），再用回调的 code 换取 openid/unionid 写入账号。 */
    @PostMapping("/wechat/bind")
    public Mono<ResponseEntity<Object>> bindWechat(@RequestBody(required = false) Map<String, String> body,
                                                   @AuthenticationPrincipal(expression = "username") String me,
                                                   ServerWebExchange exchange) {
        String code = body == null ? null : body.get("code");
        String state = body == null ? null : body.get("state");
        return Mono.defer(() -> {
                    if (me == null) return Mono.just(unauthorized());
                    return consumeState(exchange, state).flatMap(ok -> {
                        if (!ok) {
                            return Mono.just(badRequest("授权状态校验失败（state 不匹配或已过期），请重新扫码"));
                        }
                        return wechatService.exchangeCode(code)
                                .map(u -> {
                                    ProfileDto p = userService.bindWechat(me, u.openid(), u.unionid(), u.nickname());
                                    Map<String, Object> m = profileView(p);
                                    m.put("demo", u.demo());
                                    return ResponseEntity.<Object>ok(m);
                                })
                                .subscribeOn(Schedulers.boundedElastic())
                                .onErrorResume(e -> Mono.just(failure(e)));
                    });
                });
    }

    /** 解绑微信。 */
    @PostMapping("/wechat/unbind")
    public Mono<ResponseEntity<Object>> unbindWechat(@AuthenticationPrincipal(expression = "username") String me) {
        return handle(me, name -> ResponseEntity.ok(profileView(userService.unbindWechat(name))));
    }

    // ---------------- 内部工具 ----------------

    /**
     * 用新用户名重建会话中的认证身份（角色/权限保持不变），返回是否保存成功。
     * 不刷新的话，WebSession 里仍是旧用户名，后续请求会被当成"用户不存在"（表现为 404/资料加载失败）。
     */
    private Mono<Boolean> refreshPrincipal(ServerWebExchange exchange, ProfileDto p) {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(Authentication::isAuthenticated)
                .flatMap(old -> {
                    UserDetails principal = User.withUsername(p.username())
                            .password("")   // 会话对象不需要密码，仅保留用户名与权限
                            .roles(p.role())
                            .build();
                    Authentication updated = new UsernamePasswordAuthenticationToken(
                            principal, old.getCredentials(), principal.getAuthorities());
                    return securityContextRepository.save(exchange, new SecurityContextImpl(updated))
                            .thenReturn(true);
                })
                .defaultIfEmpty(false)
                .onErrorResume(e -> {
                    log.warn("改名后刷新登录身份失败：{}", e.getMessage());
                    return Mono.just(false);
                });
    }

    /** 取出并删除会话中的 state 与回调值比对；演示模式（未配置凭据）不校验。 */
    private Mono<Boolean> consumeState(ServerWebExchange exchange, String state) {
        if (!wechatService.isConfigured()) {
            return Mono.just(true);
        }
        if (state == null || state.isBlank()) {
            return Mono.just(false);
        }
        return exchange.getSession().map(s -> {
            Object expected = s.getAttributes().remove(STATE_KEY_BIND);
            return expected != null && expected.equals(state);
        });
    }

    /** 统一处理：执行同步业务 → 异常转 401/400/500。 */
    private Mono<ResponseEntity<Object>> handle(String me, Function<String, ResponseEntity<Object>> action) {
        return Mono.defer(() -> {
                    if (me == null) return Mono.just(unauthorized());
                    try {
                        return Mono.just(action.apply(me));
                    } catch (UserService.UserException e) {
                        return Mono.just(badRequest(e.getMessage()));
                    } catch (Exception e) {
                        return Mono.just(serverError(e.getMessage()));
                    }
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Map<String, Object> profileView(ProfileDto p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id());
        m.put("username", p.username());
        m.put("role", p.role());
        m.put("studentNo", p.studentNo());
        m.put("wechatBound", p.wechatBound());
        m.put("wechatNickname", p.wechatNickname());
        m.put("wechatBoundAt", p.wechatBoundAt());
        m.put("createdAt", p.createdAt());
        m.put("wechatConfigured", wechatService.isConfigured());
        return m;
    }

    private ResponseEntity<Object> failure(Throwable e) {
        if (e instanceof UserService.UserException
                || e instanceof IllegalArgumentException
                || e instanceof IllegalStateException) {
            return badRequest(e.getMessage());
        }
        return serverError(e.getMessage());
    }

    private ResponseEntity<Object> badRequest(String msg) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", orDefault(msg)));
    }

    private ResponseEntity<Object> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
    }

    private ResponseEntity<Object> serverError(String msg) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", orDefault(msg)));
    }

    private String orDefault(String msg) {
        return msg == null || msg.isBlank() ? "请求失败" : msg;
    }
}
