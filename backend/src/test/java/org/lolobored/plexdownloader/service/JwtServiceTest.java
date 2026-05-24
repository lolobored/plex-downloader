package org.lolobored.plexdownloader.service;

import org.lolobored.plexdownloader.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secret",
            "test-secret-minimum-32-chars-long-for-tests");
        ReflectionTestUtils.setField(jwtService, "expirationMs", 86400000L);
    }

    @Test
    void generateAndValidateToken() {
        User user = new User();
        user.setPlexAccountId("plex-123");
        user.setUsername("Laurent");
        user.setRole(User.Role.ADMIN);

        String token = jwtService.generateToken(user);

        assertThat(token).isNotBlank();
        assertThat(jwtService.isValid(token)).isTrue();
        assertThat(jwtService.extractPlexAccountId(token)).isEqualTo("plex-123");
    }

    @Test
    void invalidTokenReturnsFalse() {
        assertThat(jwtService.isValid("not.a.token")).isFalse();
    }

    @Test
    void expiredTokenReturnsFalse() {
        JwtService shortLived = new JwtService();
        ReflectionTestUtils.setField(shortLived, "secret",
            "test-secret-minimum-32-chars-long-for-tests");
        ReflectionTestUtils.setField(shortLived, "expirationMs", -1L);

        User user = new User();
        user.setPlexAccountId("plex-x");
        user.setUsername("x");
        user.setRole(User.Role.USER);

        String token = shortLived.generateToken(user);
        assertThat(shortLived.isValid(token)).isFalse();
    }
}
