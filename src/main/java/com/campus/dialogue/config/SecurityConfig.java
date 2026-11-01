package com.campus.dialogue.config;

import com.campus.dialogue.security.CampusPasswordEncoder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UserDetailsRepositoryReactiveAuthenticationManager;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.security.web.server.authentication.ServerAuthenticationFailureHandler;
import org.springframework.security.web.server.authentication.ServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Spring Security（WebFlux 响应式）配置——完全采用框架自带的登录体系，不再有任何自研过滤器/令牌。
 *
 * <p>认证：<b>表单登录 + 服务端 Session</b>
 * <ul>
 *   <li>登录：浏览器把 username/password 以表单格式 POST 到 {@code /api/auth/login}，
 *       {@code FormLoginWebFilter} 交给 {@link ReactiveAuthenticationManager}
 *       （{@link UserDetailsRepositoryReactiveAuthenticationManager}，即响应式版 DaoAuthenticationProvider），
 *       由 {@code CampusUserDetailsService} 查库、{@code CampusPasswordEncoder} 校验密码；</li>
 *   <li>登录态：认证结果由 {@link WebSessionServerSecurityContextRepository} 存入 WebSession，
 *       浏览器拿到 {@code SESSION} Cookie（HttpOnly），后续请求由框架自动恢复 SecurityContext；</li>
 *   <li>登出：{@code POST /api/auth/logout} 由框架的 LogoutWebFilter 处理，销毁 Session 并清 Cookie。</li>
 * </ul>
 *
 * <p>授权：全部集中在 {@code authorizeExchange} 声明（登录即可 / 仅 ADMIN / 公开）。
 *
 * <p>响应约定保持前端既有逻辑不变：
 * <ul>
 *   <li>API（{@code /api/**}）：未登录 401、越权 403，返回 JSON；</li>
 *   <li>页面：未登录 302 跳 {@code /login.html}，越权 302 跳首页。</li>
 * </ul>
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    /** 密码校验器：沿用项目既有的 SHA-256 + 盐算法，保证存量账号可继续登录。 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new CampusPasswordEncoder();
    }

    /**
     * 认证管理器：Spring Security 标准实现，配合 {@link ReactiveUserDetailsService} + {@link PasswordEncoder}。
     * 显式声明成 Bean 后，Spring Boot 不会再生成默认的内存用户（启动不会打印随机密码）。
     */
    @Bean
    public ReactiveAuthenticationManager authenticationManager(ReactiveUserDetailsService userDetailsService,
                                                               PasswordEncoder passwordEncoder) {
        UserDetailsRepositoryReactiveAuthenticationManager manager =
                new UserDetailsRepositoryReactiveAuthenticationManager(userDetailsService);
        manager.setPasswordEncoder(passwordEncoder);
        return manager;
    }

    /** SecurityContext 的存储方式：写入服务端 WebSession（有状态登录），而不是每次请求都带令牌。 */
    @Bean
    public ServerSecurityContextRepository securityContextRepository() {
        return new WebSessionServerSecurityContextRepository();
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http,
                                                         ReactiveAuthenticationManager authenticationManager,
                                                         ServerSecurityContextRepository securityContextRepository) {
        return http
                // 演示项目前后端同源、会话 Cookie 认证；生产环境应开启 CSRF 并下发 token
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                // 不使用 HTTP Basic
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                // ① 原生表单登录：处理 URL 仍然是前端在调的 /api/auth/login，只是改由框架接管
                .formLogin(form -> form
                        .loginPage("/login.html")                       // 未登录跳转的登录页（静态页）
                        // WebFlux 版没有 loginProcessingUrl()，用 matcher 指定「哪个请求算登录表单提交」
                        .requiresAuthenticationMatcher(
                                ServerWebExchangeMatchers.pathMatchers(HttpMethod.POST, "/api/auth/login"))
                        .authenticationSuccessHandler(jsonLoginSuccess()) // 成功：返回 JSON 给前端跳转
                        .authenticationFailureHandler(jsonLoginFailure())) // 失败：401 + JSON 错误信息
                // ② 原生登出：POST /api/auth/logout，框架负责清 SecurityContext + 让 Session 失效
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler((exchange, authentication) ->
                                writeJson(exchange.getExchange().getResponse(), HttpStatus.OK, "{\"ok\":true}")))
                // ③ 认证管理器 + Session 存储（关键：有状态登录）
                .authenticationManager(authenticationManager)
                .securityContextRepository(securityContextRepository)
                // 登录成功不做「回到原来访问的页面」的跳转缓存，前端自己按角色跳
                .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
                .authorizeExchange(ex -> ex
                        // ---- 公开：登录/注册页及其脚本、公共样式 ----
                        .pathMatchers("/login.html", "/login.js", "/login.css",
                                "/register.html", "/register.js", "/register.css",
                                "/styles.css", "/dialog.css", "/dialog.js",
                                "/favicon.ico").permitAll()
                        // ---- 公开：对话页外壳（游客可浏览界面；会话/聊天等数据接口仍需登录） ----
                        .pathMatchers("/", "/index.html", "/app.js").permitAll()
                        // ---- 公开：登录、注册、微信登录、登出接口 ----
                        .pathMatchers("/api/auth/login", "/api/auth/register",
                                "/api/auth/logout", "/api/auth/wechat/**").permitAll()
                        // ---- 需登录：当前用户信息（登录页会主动探测，未登录返回 401） ----
                        .pathMatchers("/api/auth/me").authenticated()
                        // ---- 需管理员：知识库后台页面与文档/用户管理、索引重建、上传、文本录入 ----
                        .pathMatchers("/admin.html", "/admin.js", "/admin.css",
                                "/api/admin/**",
                                "/api/docs/reindex", "/api/docs/upload", "/api/docs/index").hasRole("ADMIN")
                        // ---- 需登录：个人中心、聊天/会话/知识库状态/个人资料 ----
                        .pathMatchers("/profile.html", "/profile.js", "/profile.css",
                                "/api/chat", "/api/chat/**",
                                "/api/sessions", "/api/sessions/**",
                                "/api/docs/status",
                                "/api/me", "/api/me/**").authenticated()
                        // ---- 其余（图片等静态资源）放行 ----
                        .anyExchange().permitAll())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((exchange, e) -> onUnauthenticated(exchange))
                        .accessDeniedHandler((exchange, e) -> onForbidden(exchange)))
                .build();
    }

    // ---------------- 登录成功/失败的 JSON 响应 ----------------

    /** 登录成功：返回 {@code {"username":..,"role":..}}，前端据此跳转（ADMIN → 后台，USER → 对话页）。 */
    private ServerAuthenticationSuccessHandler jsonLoginSuccess() {
        return (WebFilterExchange exchange, Authentication authentication) -> {
            String role = authentication.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .filter(a -> a.startsWith("ROLE_"))
                    .map(a -> a.substring("ROLE_".length()))
                    .findFirst().orElse("USER");
            String body = "{\"username\":\"" + escapeJson(authentication.getName())
                    + "\",\"role\":\"" + escapeJson(role) + "\"}";
            return writeJson(exchange.getExchange().getResponse(), HttpStatus.OK, body);
        };
    }

    /** 登录失败：统一 401 + 中文提示（不区分「用户不存在」与「密码错误」，避免账号枚举）。 */
    private ServerAuthenticationFailureHandler jsonLoginFailure() {
        return (WebFilterExchange exchange, org.springframework.security.core.AuthenticationException e) ->
                writeJson(exchange.getExchange().getResponse(), HttpStatus.UNAUTHORIZED,
                        "{\"error\":\"用户名或密码错误\"}");
    }

    // ---------------- 未登录 / 越权 ----------------

    private boolean isApi(ServerWebExchange exchange) {
        return exchange.getRequest().getURI().getPath().startsWith("/api/");
    }

    /** 未登录：API 返回 401 JSON，页面跳登录页。 */
    private Mono<Void> onUnauthenticated(ServerWebExchange exchange) {
        return isApi(exchange)
                ? writeJson(exchange.getResponse(), HttpStatus.UNAUTHORIZED, "{\"error\":\"unauthorized\"}")
                : redirect(exchange.getResponse(), "/login.html");
    }

    /** 已登录但无权限：API 返回 403 JSON，页面跳回首页。 */
    private Mono<Void> onForbidden(ServerWebExchange exchange) {
        return isApi(exchange)
                ? writeJson(exchange.getResponse(), HttpStatus.FORBIDDEN, "{\"error\":\"forbidden\"}")
                : redirect(exchange.getResponse(), "/");
    }

    private Mono<Void> writeJson(ServerHttpResponse response, HttpStatus status, String body) {
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    private Mono<Void> redirect(ServerHttpResponse response, String location) {
        response.setStatusCode(HttpStatus.FOUND);
        response.getHeaders().setLocation(URI.create(location));
        // 显式写一个空 body 提交响应：只用 setComplete() 时，
        // 「访问被拒」路径上状态码可能不生效（实测会变成 200），这里强制提交 302。
        DataBuffer empty = response.bufferFactory().wrap(new byte[0]);
        return response.writeWith(Mono.just(empty));
    }

    private String escapeJson(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
