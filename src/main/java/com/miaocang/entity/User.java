package com.miaocang.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 用户（Wiki 主人）：登录主体。每个用户拥有一片专属工作区根目录
 * （<library.dir>/<username>/），其领养的每只猫是根目录下的一个子目录
 * （<username>/cat-<id>/），目录内部结构保持不变。
 */
@Entity
@Table(name = "mc_user")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户名（登录账号），全局唯一，同时作为其工作区根目录名 */
    @Column(nullable = false, unique = true, length = 50)
    private String username;

    /** BCrypt 密码散列（参照 citadel：BCryptPasswordEncoder） */
    @Column(nullable = false, length = 100)
    private String passwordHash;

    /** 昵称（展示用，可空） */
    @Column(length = 50)
    private String displayName;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
