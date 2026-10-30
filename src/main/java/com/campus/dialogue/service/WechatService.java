package com.campus.dialogue.service;

import com.campus.dialogue.config.AppProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * 微信对接（个人中心「绑定微信」）。
 *
 * 真实链路（微信开放平台 · 网站应用扫码登录）：
 *   1. 前端跳转 {@link #authorizeUrl(String)} 让用户扫码，微信回调 profile.html?code=xxx
 *   2. 后端用 code 调 /sns/oauth2/access_token 换取 openid + access_token
 *   3. 可选调 /sns/userinfo 取昵称
 *
 * 未配置 app-id/app-secret 时进入「演示模式」：不访问微信服务器，由 code 派生稳定的演示 openid，
 * 保证本地与测试环境可以把绑定流程跑通；页面会明确提示当前为演示绑定。
 */
@Service
public class WechatService {

    /** 绑定结果：openid + 昵称 + 是否为演示模式。 */
    public record WechatUser(String openid, String nickname, boolean demo) {}

    private final AppProperties.Wechat cfg;
    private final WebClient wechatWebClient;

    public WechatService(AppProperties props, @Qualifier("wechatWebClient") WebClient wechatWebClient) {
        this.cfg = props.getWechat();
        this.wechatWebClient = wechatWebClient;
    }

    public boolean isConfigured() {
        return cfg.isConfigured();
    }

    /**
     * 生成微信扫码授权地址；未配置凭据时返回 null（前端切演示模式）。
     * 默认回调到「个人中心」(redirectUri)；登录场景传 login=true 会回调到登录页。
     */
    public String authorizeUrl(String state, boolean forLogin) {
        if (!cfg.isConfigured()) return null;
        String redirect = URLEncoder.encode(forLogin ? cfg.getLoginRedirectUri() : cfg.getRedirectUri(), StandardCharsets.UTF_8);
        String st = URLEncoder.encode(state == null ? "" : state, StandardCharsets.UTF_8);
        return "https://open.weixin.qq.com/connect/qrconnect"
                + "?appid=" + cfg.getAppId()
                + "&redirect_uri=" + redirect
                + "&response_type=code"
                + "&scope=" + cfg.getScope()
                + "&state=" + st
                + "#wechat_redirect";
    }

    /** 默认回调到个人中心的授权地址（绑定微信用）。 */
    public String authorizeUrl(String state) {
        return authorizeUrl(state, false);
    }

    /** 用授权 code 换取微信身份；未配置凭据时返回演示身份。 */
    @SuppressWarnings("unchecked")
    public Mono<WechatUser> exchangeCode(String code) {
        if (!cfg.isConfigured()) {
            return Mono.just(demoUser(code));
        }
        if (code == null || code.isBlank()) {
            return Mono.error(new IllegalArgumentException("缺少微信授权 code"));
        }
        return wechatWebClient.get()
                .uri(b -> b.path("/sns/oauth2/access_token")
                        .queryParam("appid", cfg.getAppId())
                        .queryParam("secret", cfg.getAppSecret())
                        .queryParam("code", code)
                        .queryParam("grant_type", "authorization_code")
                        .build())
                .retrieve()
                .bodyToMono(Map.class)
                .flatMap(resp -> {
                    Object errcode = resp.get("errcode");
                    if (errcode != null) {
                        return Mono.error(new IllegalStateException(
                                "微信授权失败(" + errcode + ")：" + resp.get("errmsg")));
                    }
                    String openid = (String) resp.get("openid");
                    String accessToken = (String) resp.get("access_token");
                    if (openid == null) {
                        return Mono.error(new IllegalStateException("微信授权失败：未返回 openid"));
                    }
                    return fetchNickname(accessToken, openid)
                            .map(nick -> new WechatUser(openid, nick, false));
                });
    }

    /** 拉取微信昵称，失败则降级为「微信用户」（不影响绑定）。 */
    @SuppressWarnings("unchecked")
    private Mono<String> fetchNickname(String accessToken, String openid) {
        return wechatWebClient.get()
                .uri(b -> b.path("/sns/userinfo")
                        .queryParam("access_token", accessToken)
                        .queryParam("openid", openid)
                        .queryParam("lang", "zh_CN")
                        .build())
                .retrieve()
                .bodyToMono(Map.class)
                .map(resp -> {
                    Object nick = resp.get("nickname");
                    return nick == null ? "微信用户" : nick.toString();
                })
                .onErrorReturn("微信用户");
    }

    private WechatUser demoUser(String code) {
        String seed = (code == null || code.isBlank()) ? String.valueOf(System.nanoTime()) : code;
        String hash = sha256Hex(seed).substring(0, 24);
        return new WechatUser("demo_" + hash, "微信用户" + hash.substring(0, 4), true);
    }

    private String sha256Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
