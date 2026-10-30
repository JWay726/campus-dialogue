package com.campus.dialogue.controller;

import com.campus.dialogue.dto.ProfileDto;
import com.campus.dialogue.service.UserService;
import com.campus.dialogue.service.WechatService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
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

    private final UserService userService;
    private final WechatService wechatService;

    public MeController(UserService userService, WechatService wechatService) {
        this.userService = userService;
        this.wechatService = wechatService;
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

    /** 微信授权地址：未配置开放平台凭据时返回 demo=true（前端展示演示绑定）。 */
    @GetMapping("/wechat/authorize-url")
    public Mono<ResponseEntity<Object>> wechatAuthorizeUrl(@AuthenticationPrincipal(expression = "username") String me) {
        return handle(me, name -> {
            String url = wechatService.authorizeUrl(name);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("demo", url == null);
            m.put("url", url);
            m.put("configured", wechatService.isConfigured());
            return ResponseEntity.ok(m);
        });
    }

    /** 微信绑定：传入微信回调的 code，换取 openid 后写入账号。 */
    @PostMapping("/wechat/bind")
    public Mono<ResponseEntity<Object>> bindWechat(@RequestBody(required = false) Map<String, String> body,
                                                   @AuthenticationPrincipal(expression = "username") String me) {
        String code = body == null ? null : body.get("code");
        return Mono.defer(() -> {
                    if (me == null) return Mono.just(unauthorized());
                    return wechatService.exchangeCode(code)
                            .map(u -> {
                                ProfileDto p = userService.bindWechat(me, u.openid(), u.nickname());
                                Map<String, Object> m = profileView(p);
                                m.put("demo", u.demo());
                                return ResponseEntity.<Object>ok(m);
                            })
                            .subscribeOn(Schedulers.boundedElastic());
                })
                .onErrorResume(e -> Mono.just(failure(e)));
    }

    /** 解绑微信。 */
    @PostMapping("/wechat/unbind")
    public Mono<ResponseEntity<Object>> unbindWechat(@AuthenticationPrincipal(expression = "username") String me) {
        return handle(me, name -> ResponseEntity.ok(profileView(userService.unbindWechat(name))));
    }

    // ---------------- 内部工具 ----------------

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
