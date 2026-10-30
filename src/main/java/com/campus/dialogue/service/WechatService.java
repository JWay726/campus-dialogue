package com.campus.dialogue.service;

import com.campus.dialogue.config.AppProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Map;

/**
 * 微信对接（微信开放平台 · 网站应用扫码登录 / 个人中心绑定）。
 *
 * 真实链路：
 *   1. 前端跳转 {@link #authorizeUrl(String, boolean)} 让用户扫码，微信带 code + state 回跳到站点页面
 *   2. 后端先用会话里的 state 校验来源（防 CSRF），再用 code 调 /sns/oauth2/access_token 换 openid + unionid
 *   3. 可选调 /sns/userinfo 取昵称
 *
 * 身份标识取舍：
 *   - <b>openid</b>：本应用内唯一，换 AppID 或以后接入其它端就会变；
 *   - <b>unionid</b>：同一开放平台账号下唯一，跨应用不变，因此优先用 unionid 找账号。
 *   开放平台账号未绑定或用户未关注时微信不返回 unionid，此时退回 openid（字段为 null）。
 *
 * 未配置 app-id/app-secret 时进入「演示模式」：不访问微信服务器，由 code 派生稳定的演示 openid，
 * 保证本地与测试环境可以把绑定流程跑通；页面会明确提示当前为演示绑定。
 */
@Service
public class WechatService {

    /**
     * 绑定/登录结果。
     *
     * @param openid  本应用内唯一标识（必有）
     * @param unionid 开放平台账号内唯一标识（可能为 null）
     * @param demo    是否为演示模式产生的假身份
     */
    public record WechatUser(String openid, String unionid, String nickname, boolean demo) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

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
     * 生成一次性 state（32 位十六进制随机串）。
     * 调用方负责把它存进当前 WebSession，回调时取出比对并删除——只认一次，用完即失效。
     */
    public static String newState() {
        byte[] buf = new byte[16];
        RANDOM.nextBytes(buf);
        StringBuilder sb = new StringBuilder(buf.length * 2);
        for (byte b : buf) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /**
     * 生成微信扫码授权地址；未配置凭据时返回 null（前端切演示模式）。
     * 默认回调到「个人中心」(redirectUri)；登录场景传 forLogin=true 会回调到登录页。
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
            return Mono.error(new IllegalArgumentException("缺少微信授权 code，请重新扫码"));
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
                        return Mono.error(new IllegalStateException(friendlyError(errcode, resp.get("errmsg"))));
                    }
                    String openid = str(resp.get("openid"));
                    String unionid = str(resp.get("unionid"));
                    String accessToken = str(resp.get("access_token"));
                    if (openid == null) {
                        return Mono.error(new IllegalStateException("微信授权失败：未返回 openid，请重新扫码"));
                    }
                    return fetchNickname(accessToken, openid)
                            .map(nick -> new WechatUser(openid, blankToNull(unionid), nick, false));
                })
                .onErrorMap(WebClientRequestException.class,
                        e -> new IllegalStateException("无法连接微信服务器，请检查网络或代理设置（" + e.getMessage() + "）"));
    }

    /** 拉取微信昵称，失败则降级为「微信用户」（不影响登录/绑定）。 */
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

    /**
     * 把微信错误码翻译成用户能看懂的中文（保留 errcode 便于排查）。
     * 文档：https://developers.weixin.qq.com/doc/oplatform/Website_App/WeChat_Login/Wechat_Login.html
     */
    static String friendlyError(Object errcode, Object errmsg) {
        String code = String.valueOf(errcode);
        String hint = switch (code) {
            case "40029" -> "微信授权 code 无效或已过期，请重新扫码";
            case "40163" -> "微信授权 code 已被使用（code 只能用一次），请重新扫码";
            case "40013" -> "微信 AppID 无效，请检查 campus.wechat.app-id 是否与开放平台一致";
            case "40125" -> "微信 AppSecret 无效，请检查 campus.wechat.app-secret";
            case "41008" -> "缺少微信授权 code，请重新扫码";
            case "40222" -> "操作过于频繁，请稍后再试";
            default -> null;
        };
        if (hint != null) {
            return hint + "（errcode=" + code + "）";
        }
        return "微信授权失败(errcode=" + code + ")：" + (errmsg == null ? "未知错误" : errmsg);
    }

    private WechatUser demoUser(String code) {
        String seed = (code == null || code.isBlank()) ? String.valueOf(System.nanoTime()) : code;
        String hash = sha256Hex(seed).substring(0, 24);
        return new WechatUser("demo_" + hash, null, "微信用户" + hash.substring(0, 4), true);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
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
