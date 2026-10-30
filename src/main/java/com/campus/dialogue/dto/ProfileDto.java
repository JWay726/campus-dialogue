package com.campus.dialogue.dto;

/**
 * 个人中心展示用的账号资料（不含密码哈希与微信 openid 原文）。
 *
 * @param wechatBound 是否已绑定微信
 * @param wechatDemo  绑定是否来自演示模式（未配置微信开放平台凭据）
 */
public record ProfileDto(
        Long id,
        String username,
        String role,
        String studentNo,
        boolean wechatBound,
        String wechatNickname,
        Long wechatBoundAt,
        Long createdAt
) {}
