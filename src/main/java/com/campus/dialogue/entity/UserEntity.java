package com.campus.dialogue.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

/**
 * 系统账号：管理员（ADMIN，可见并管理知识库后台）与普通用户（USER，仅对话）。
 * 密码以加盐哈希存储（见 com.campus.dialogue.security.Passwords），不存明文。
 */
@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(name = "uk_users_username", columnNames = "username"))
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @JsonIgnore
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String role;

    /** 学号（个人中心绑定，全表唯一；未绑定时为 null）。 */
    @Column(name = "student_no", unique = true, length = 32)
    private String studentNo;

    /** 微信 openid：唯一标识，不外泄（接口仅返回「是否已绑定」）。 */
    @JsonIgnore
    @Column(name = "wechat_openid", length = 64)
    private String wechatOpenid;

    @Column(name = "wechat_nickname", length = 64)
    private String wechatNickname;

    @Column(name = "wechat_bound_at")
    private Long wechatBoundAt;

    @Column(name = "created_at")
    private Long createdAt;

    @Column(name = "updated_at")
    private Long updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getStudentNo() { return studentNo; }
    public void setStudentNo(String studentNo) { this.studentNo = studentNo; }

    public String getWechatOpenid() { return wechatOpenid; }
    public void setWechatOpenid(String wechatOpenid) { this.wechatOpenid = wechatOpenid; }

    public String getWechatNickname() { return wechatNickname; }
    public void setWechatNickname(String wechatNickname) { this.wechatNickname = wechatNickname; }

    public Long getWechatBoundAt() { return wechatBoundAt; }
    public void setWechatBoundAt(Long wechatBoundAt) { this.wechatBoundAt = wechatBoundAt; }

    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }

    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
}
