package com.info.platform.domain.common;

import java.time.Instant;
import java.util.Objects;

/**
 * 用户实体（本地用户表 {@code user}）。
 *
 * <p>领域层纯净：仅依赖 JDK 类型，可脱离容器单测、可移植。持久化字段（id/version/时间戳） 由基础设施层 {@code UserRepositoryImpl} 经 {@link
 * #reconstruct} 回填。承载 JWT 主体身份（userId/username）， 供 T11 watchlist 行级权限取 {@code userId}。
 */
public class User {

    private Long id;
    private String username;
    private String passwordHash;
    private long version;
    private Instant createdAt;
    private Instant updatedAt;

    private User() {}

    /** 从持久化数据重建实体（基础设施层落库后回读时用）。 */
    public static User reconstruct(
            Long id,
            String username,
            String passwordHash,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        User u = new User();
        u.id = id;
        u.username = username;
        u.passwordHash = passwordHash;
        u.version = version;
        u.createdAt = createdAt;
        u.updatedAt = updatedAt;
        return u;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** 校验原始密码是否与持久化哈希匹配（领域行为：委托 {@link PasswordEncoder} 端口）。 */
    public boolean passwordMatches(String rawPassword, PasswordEncoder encoder) {
        Objects.requireNonNull(encoder, "encoder 必填");
        return encoder.matches(rawPassword, this.passwordHash);
    }
}
