package com.campus.dialogue.service;

import com.campus.dialogue.config.AppProperties;
import com.campus.dialogue.dto.ProfileDto;
import com.campus.dialogue.entity.UserEntity;
import com.campus.dialogue.repository.UserRepository;
import com.campus.dialogue.security.Passwords;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 账号服务：登录校验、开放注册（默认 USER）、管理员创建/删除账号、列表。
 * 账号持久化到 users 表，密码加盐哈希存储。首次启动若表为空，用配置中的 admin 账号灌入默认管理员。
 */
@Service
public class UserService {

    /** 业务校验失败异常；Controller 捕获后转 400。 */
    public static class UserException extends RuntimeException {
        public UserException(String msg) { super(msg); }
    }

    private final UserRepository repo;
    private final AppProperties.Auth auth;

    public UserService(UserRepository repo, AppProperties appProperties) {
        this.repo = repo;
        this.auth = appProperties.getAuth();
    }

    /** 首次启动且用户表为空时，用配置中的 admin 账号灌入默认管理员（密码加盐哈希）。 */
    public void seedIfEmpty() {
        if (repo.count() > 0) return;
        AppProperties.Auth.Account admin = auth.getAdmin();
        UserEntity u = new UserEntity();
        u.setUsername(admin.getUsername());
        u.setPasswordHash(Passwords.hash(admin.getPassword()));
        u.setRole(admin.getRole());
        long now = System.currentTimeMillis();
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        repo.save(u);
        System.out.println("[campus-dialogue] 已创建默认管理员账号：" + admin.getUsername()
                + "（角色 " + admin.getRole() + "）");
    }

    // 说明：用户名/密码登录校验已迁移到 Spring Security
    // （CampusUserDetailsService 查库 + CampusPasswordEncoder 比对），本类不再提供 authenticate()。

    /** 按微信 openid 查账号（无则空）。 */
    public Optional<UserEntity> findByWechatOpenid(String openid) {
        return repo.findByWechatOpenid(openid);
    }

    /**
     * 微信扫码登录：优先按 unionid 找账号（跨应用稳定），找不到再按 openid；
     * 都没有则自动注册一个 USER 账号并绑定该微信后登录。
     * 自动注册的用户名形如 wxXXXX（X 取自 openid 末段，保证唯一），密码随机（扫码登录不需要密码）。
     */
    public UserEntity loginOrRegisterByWechat(String openid, String unionid, String nickname) {
        if (openid == null || openid.isBlank()) {
            throw new UserException("微信授权失败：未获取到 openid");
        }
        String uid = blankToNull(unionid);
        return findByWechatIdentity(uid, openid)
                .orElseGet(() -> {
                    String base = "wx" + (openid.length() > 8 ? openid.substring(openid.length() - 8) : openid)
                            .replaceAll("[^a-zA-Z0-9]", "");
                    String username = ensureUniqueUsername(base);
                    long now = System.currentTimeMillis();
                    UserEntity u = new UserEntity();
                    u.setUsername(username);
                    u.setPasswordHash(Passwords.hash(generateRandomPwd()));
                    u.setRole("USER");
                    u.setWechatOpenid(openid);
                    u.setWechatUnionid(uid);
                    u.setWechatNickname(nickname);
                    u.setWechatBoundAt(now);
                    u.setCreatedAt(now);
                    u.setUpdatedAt(now);
                    repo.save(u);
                    System.out.println("[campus-dialogue] 微信扫码首次登录，已自动创建账号：" + username);
                    return u;
                });
    }

    /** unionid 优先、openid 兜底：unionid 能为空（开放平台未绑定时微信不返回）。 */
    private Optional<UserEntity> findByWechatIdentity(String unionid, String openid) {
        if (unionid != null) {
            Optional<UserEntity> byUnionid = repo.findByWechatUnionid(unionid);
            if (byUnionid.isPresent()) return byUnionid;
        }
        return repo.findByWechatOpenid(openid);
    }

    private String ensureUniqueUsername(String base) {
        String candidate = base;
        int i = 1;
        while (repo.existsByUsername(candidate)) {
            candidate = base + (i++);
        }
        return candidate;
    }

    private String generateRandomPwd() {
        return Passwords.hash(String.valueOf(System.nanoTime() ^ (long) (Math.random() * 1e9))).substring(0, 16);
    }

    /** 开放注册：默认 USER 角色。用户名 3-32 位、密码至少 6 位、用户名唯一。 */
    public UserEntity register(String username, String password) {
        validateUsername(username);
        validatePassword(password);
        if (repo.existsByUsername(username)) {
            throw new UserException("用户名已被占用");
        }
        return saveUser(username, password, "USER");
    }

    /** 管理员创建账号：可指定角色（ADMIN / USER）。 */
    public UserEntity createUser(String username, String password, String role) {
        validateUsername(username);
        validatePassword(password);
        if (!"ADMIN".equals(role) && !"USER".equals(role)) {
            throw new UserException("角色必须是 ADMIN 或 USER");
        }
        if (repo.existsByUsername(username)) {
            throw new UserException("用户名已被占用");
        }
        return saveUser(username, password, role);
    }

    public List<UserEntity> list() {
        return repo.findAll();
    }

    public void deleteUser(Long id) {
        repo.findById(id).ifPresent(repo::delete);
    }

    // ---------------- 个人中心 ----------------

    public UserEntity require(String username) {
        return repo.findByUsername(username)
                .orElseThrow(() -> new UserException("用户不存在"));
    }

    public ProfileDto profileOf(String username) {
        UserEntity u = require(username);
        return toProfile(u);
    }

    /**
     * 绑定/修改学号；传空值表示解绑。学号格式 4-32 位字母或数字，且全站唯一。
     * 说明：当前仅做格式与唯一性校验，后续可在此处调用教务系统接口核实学号归属。
     */
    public ProfileDto updateStudentNo(String username, String studentNo) {
        UserEntity u = require(username);
        String v = studentNo == null ? "" : studentNo.trim();
        if (v.isEmpty()) {
            u.setStudentNo(null);
        } else {
            if (!v.matches("[A-Za-z0-9]{4,32}")) {
                throw new UserException("学号格式不正确（4-32 位字母或数字）");
            }
            boolean occupied = repo.findByStudentNo(v)
                    .filter(other -> !other.getId().equals(u.getId()))
                    .isPresent();
            if (occupied) {
                throw new UserException("该学号已被其他账号绑定");
            }
            u.setStudentNo(v);
        }
        u.setUpdatedAt(System.currentTimeMillis());
        repo.save(u);
        return toProfile(u);
    }

    /** 绑定微信：openid / unionid 全站唯一，重复绑定直接拒绝。 */
    public ProfileDto bindWechat(String username, String openid, String unionid, String nickname) {
        UserEntity u = require(username);
        if (openid == null || openid.isBlank()) {
            throw new UserException("微信授权失败：未获取到 openid");
        }
        String uid = blankToNull(unionid);
        boolean occupied = repo.findByWechatOpenid(openid)
                .filter(other -> !other.getId().equals(u.getId()))
                .isPresent()
                || (uid != null && repo.findByWechatUnionid(uid)
                .filter(other -> !other.getId().equals(u.getId()))
                .isPresent());
        if (occupied) {
            throw new UserException("该微信已绑定其他账号，请先解绑原账号");
        }
        long now = System.currentTimeMillis();
        u.setWechatOpenid(openid);
        u.setWechatUnionid(uid);
        u.setWechatNickname(nickname);
        u.setWechatBoundAt(now);
        u.setUpdatedAt(now);
        repo.save(u);
        return toProfile(u);
    }

    public ProfileDto unbindWechat(String username) {
        UserEntity u = require(username);
        u.setWechatOpenid(null);
        u.setWechatUnionid(null);
        u.setWechatNickname(null);
        u.setWechatBoundAt(null);
        u.setUpdatedAt(System.currentTimeMillis());
        repo.save(u);
        return toProfile(u);
    }

    /** 修改密码：校验原密码后再存新哈希。 */
    public void changePassword(String username, String oldPassword, String newPassword) {
        UserEntity u = require(username);
        if (oldPassword == null || !Passwords.matches(oldPassword, u.getPasswordHash())) {
            throw new UserException("原密码不正确");
        }
        validatePassword(newPassword);
        if (Passwords.matches(newPassword, u.getPasswordHash())) {
            throw new UserException("新密码不能与原密码相同");
        }
        u.setPasswordHash(Passwords.hash(newPassword));
        u.setUpdatedAt(System.currentTimeMillis());
        repo.save(u);
    }

    private ProfileDto toProfile(UserEntity u) {
        return new ProfileDto(
                u.getId(), u.getUsername(), u.getRole(), u.getStudentNo(),
                u.getWechatOpenid() != null, u.getWechatNickname(), u.getWechatBoundAt(),
                u.getCreatedAt());
    }

    private UserEntity saveUser(String username, String password, String role) {
        UserEntity u = new UserEntity();
        u.setUsername(username);
        u.setPasswordHash(Passwords.hash(password));
        u.setRole(role);
        long now = System.currentTimeMillis();
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        return repo.save(u);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private void validateUsername(String username) {
        if (username == null || username.trim().length() < 3 || username.length() > 32) {
            throw new UserException("用户名长度需为 3-32 位");
        }
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 6) {
            throw new UserException("密码长度至少 6 位");
        }
    }
}
