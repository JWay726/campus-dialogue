package com.campus.dialogue.service;

import com.campus.dialogue.config.AppProperties;
import com.campus.dialogue.dto.ProfileDto;
import com.campus.dialogue.entity.UserEntity;
import com.campus.dialogue.repository.UserRepository;
import com.campus.dialogue.security.Passwords;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 账号服务：登录校验、开放注册（默认 USER）、管理员创建/删除账号、列表。
 * 账号持久化到 users 表，密码加盐哈希存储。首次启动若表为空，用配置中的 admin 账号灌入默认管理员。
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    /** 业务校验失败异常；Controller 捕获后转 400。 */
    public static class UserException extends RuntimeException {
        public UserException(String msg) { super(msg); }
    }

    private final UserRepository repo;
    private final AppProperties.Auth auth;

    /**
     * 改名时要同步会话归属。用字段注入而非构造器注入：单测里直接 new UserService 时可以为空，
     * 不影响其余逻辑（为空时跳过会话改名）。
     */
    @Autowired(required = false)
    private SessionService sessionService;

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
        // 用户名与学号共用一个登录入口，生成的名字也要避开别人绑定的学号
        while (repo.existsByUsername(candidate) || repo.findByStudentNo(candidate).isPresent()) {
            candidate = base + (i++);
        }
        return candidate;
    }

    private String generateRandomPwd() {
        return Passwords.hash(String.valueOf(System.nanoTime() ^ (long) (Math.random() * 1e9))).substring(0, 16);
    }

    /** 开放注册：默认 USER 角色。用户名 3-32 位、密码至少 6 位、登录标识（用户名/学号）唯一。 */
    public UserEntity register(String username, String password) {
        validateUsername(username);
        validatePassword(password);
        ensureUsernameFree(username.trim(), null);
        return saveUser(username.trim(), password, "USER");
    }

    /** 管理员创建账号：可指定角色（ADMIN / USER）。 */
    public UserEntity createUser(String username, String password, String role) {
        validateUsername(username);
        validatePassword(password);
        if (!"ADMIN".equals(role) && !"USER".equals(role)) {
            throw new UserException("角色必须是 ADMIN 或 USER");
        }
        ensureUsernameFree(username.trim(), null);
        return saveUser(username.trim(), password, role);
    }

    public List<UserEntity> list() {
        return repo.findAll();
    }

    /**
     * 删除账号，并清理其名下会话与消息。
     * 不清理的话会留下孤儿会话：以后有人注册/改名为同名，就会看到上一个人的历史对话。
     */
    @Transactional
    public void deleteUser(Long id) {
        repo.findById(id).ifPresent(u -> {
            if (sessionService != null) {
                int n = sessionService.deleteAllOf(u.getUsername());
                if (n > 0) {
                    log.info("删除账号 {} 时清理了 {} 个会话", u.getUsername(), n);
                }
            }
            repo.delete(u);
        });
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

    /** 用户名对应的学号（未绑定或用户不存在时为空），供登录态接口展示「可用学号登录」。 */
    public Optional<String> studentNoOf(String username) {
        return repo.findByUsername(username).map(UserEntity::getStudentNo);
    }

    /**
     * 绑定/修改学号；传空值表示解绑。学号格式 4-32 位字母或数字，且全站唯一。
     *
     * <p>绑定成功后即可用<b>学号代替用户名登录</b>（见 CampusUserDetailsService），
     * 因此学号必须同时避开别人的用户名，否则同一串字符会对应两个账号。
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
            ensureStudentNoFree(v, u.getId());
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

    /**
     * 修改用户名（即登录账号）。要求校验当前密码，防止他人趁电脑未锁屏改走账号。
     *
     * <p>用户名唯一且是会话归属键，所以改名时：
     * <ul>
     *   <li>新用户名不能与任何账号重复（含自己同名，直接拒绝）；</li>
     *   <li>把该用户名下的历史会话一并改归属，否则改名后自己的对话记录会"消失"；</li>
     *   <li>密码、学号、微信绑定与角色全部保持不变，管理员用户列表读的是同一张表，自动同步。</li>
     * </ul>
     */
    @Transactional
    public ProfileDto changeUsername(String currentUsername, String newUsername, String password) {
        UserEntity u = require(currentUsername);
        if (password == null || !Passwords.matches(password, u.getPasswordHash())) {
            throw new UserException("当前密码不正确");
        }
        String target = newUsername == null ? "" : newUsername.trim();
        if (target.equals(u.getUsername())) {
            throw new UserException("新用户名与当前用户名相同");
        }
        validateUsername(target);
        ensureUsernameFree(target, u.getId());
        u.setUsername(target);
        u.setUpdatedAt(System.currentTimeMillis());
        repo.save(u);

        int moved = 0;
        if (sessionService != null) {
            moved = sessionService.renameOwner(currentUsername, target);
        }
        log.info("用户 {} 改名为 {}，同步迁移历史会话 {} 个", currentUsername, target, moved);
        return toProfile(u);
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

    // ---------------- 登录标识唯一性 ----------------
    // 用户名和学号都能登录，所以两者在「全站范围内」互相不能撞车：
    // 别人的学号不能拿来当用户名，别人的用户名也不能拿来当学号，
    // 否则同一串字符会指向两个账号，登录到底进谁家就说不清了。

    /** 注册 / 改名时校验：该用户名既不能是别人的用户名，也不能是别人绑定的学号。 */
    private void ensureUsernameFree(String username, Long excludeUserId) {
        if (takenByOther(repo.findByUsername(username), excludeUserId)) {
            throw new UserException("用户名已被占用");
        }
        if (takenByOther(repo.findByStudentNo(username), excludeUserId)) {
            throw new UserException("该用户名已被其他账号作为学号使用，请换一个");
        }
    }

    /** 绑定学号时校验：该学号既不能是别人绑定的学号，也不能是别人的用户名。 */
    private void ensureStudentNoFree(String studentNo, Long excludeUserId) {
        if (takenByOther(repo.findByStudentNo(studentNo), excludeUserId)) {
            throw new UserException("该学号已被其他账号绑定");
        }
        if (takenByOther(repo.findByUsername(studentNo), excludeUserId)) {
            throw new UserException("该学号已被其他账号作为用户名使用，请换一个");
        }
    }

    private static boolean takenByOther(Optional<UserEntity> found, Long excludeUserId) {
        return found.filter(o -> !Objects.equals(o.getId(), excludeUserId)).isPresent();
    }

    private void validateUsername(String username) {
        String v = username == null ? "" : username.trim();
        if (v.isEmpty()) {
            throw new UserException("用户名不能为空");
        }
        if (v.length() < 3 || v.length() > 32) {
            throw new UserException("用户名长度需为 3-32 位");
        }
        if (v.matches(".*\\s.*")) {
            throw new UserException("用户名不能包含空格");
        }
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 6) {
            throw new UserException("密码长度至少 6 位");
        }
    }
}
