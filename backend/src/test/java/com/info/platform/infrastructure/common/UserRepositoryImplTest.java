package com.info.platform.infrastructure.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.common.PasswordEncoder;
import com.info.platform.domain.common.User;
import com.info.platform.domain.common.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * UserRepositoryImpl 集成测试（T17）：@SpringBootTest 启动完整上下文（含 Flyway V3 迁移与 admin 播种）， 测
 * findByUsername/existsByUsername 往返，并验证播种用户的 password_hash 可被初始密码 "admin123" 校验通过（端到端防脱节）。
 */
@SpringBootTest
@ActiveProfiles("test")
class UserRepositoryImplTest {

    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    void seededAdmin_canBeFoundByUsername() {
        Optional<User> admin = userRepository.findByUsername("admin");

        assertThat(admin).as("V3 应播种 admin 用户").isPresent();
        User user = admin.get();
        assertThat(user.getId()).isNotNull();
        assertThat(user.getUsername()).isEqualTo("admin");
        assertThat(user.getPasswordHash()).startsWith("$2a$10$");
        assertThat(user.getVersion()).isZero();
    }

    @Test
    void seededAdmin_passwordMatchesInitialPassword() {
        // 端到端：V3 播种的 BCrypt 哈希必须能校验初始密码 "admin123"，否则登录种子用户必败
        User admin = userRepository.findByUsername("admin").orElseThrow();
        assertThat(passwordEncoder.matches("admin123", admin.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches("not-the-password", admin.getPasswordHash())).isFalse();
    }

    @Test
    void existsByUsername_seededAdmin_true() {
        assertThat(userRepository.existsByUsername("admin")).isTrue();
    }

    @Test
    void existsByUsername_unknown_false() {
        assertThat(userRepository.existsByUsername("nobody")).isFalse();
    }

    @Test
    void findByUsername_unknown_returnsEmpty() {
        assertThat(userRepository.findByUsername("nobody")).isEmpty();
    }
}
