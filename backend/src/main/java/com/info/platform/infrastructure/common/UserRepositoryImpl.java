package com.info.platform.infrastructure.common;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.info.platform.domain.common.User;
import com.info.platform.domain.common.UserRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link UserRepository} 端口的 SQLite/MyBatis-Plus 实现（基础设施层）。
 *
 * <p>PO↔Entity 转换集中于此；时间戳存 ISO-8601 文本；乐观锁由 {@code @Version} 守护（用户改密等写操作）。
 * 仅读路径（登录按用户名加载、判占用），无注册写路径（首版用户由 V3 迁移播种，改密接口后续补）。
 */
@Repository
public class UserRepositoryImpl implements UserRepository {

    private final UserMapper mapper;

    public UserRepositoryImpl(UserMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<User> findByUsername(String username) {
        UserPO po =
                mapper.selectOne(
                        new LambdaQueryWrapper<UserPO>().eq(UserPO::getUsername, username));
        return Optional.ofNullable(po).map(UserRepositoryImpl::toEntity);
    }

    @Override
    public boolean existsByUsername(String username) {
        return mapper.exists(new LambdaQueryWrapper<UserPO>().eq(UserPO::getUsername, username));
    }

    private static User toEntity(UserPO po) {
        return User.reconstruct(
                po.getId(),
                po.getUsername(),
                po.getPasswordHash(),
                po.getVersion() == null ? 0L : po.getVersion().longValue(),
                po.getCreatedAt() == null ? null : Instant.parse(po.getCreatedAt()),
                po.getUpdatedAt() == null ? null : Instant.parse(po.getUpdatedAt()));
    }
}
