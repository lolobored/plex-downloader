package org.lolobored.plexdownloader.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;

@Data @Entity @Table(name = "users")
public class User {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "plex_account_id", unique = true, nullable = false)
    private String plexAccountId;
    @Column(nullable = false)
    private String username;
    @Column(name = "avatar_url")
    private String avatarUrl;
    @Column(name = "plex_token")
    private String plexToken;
    @Enumerated(EnumType.STRING)
    private Role role = Role.USER;
    @Column(name = "last_login_at")
    private Instant lastLoginAt;
    @Column(name = "created_at", updatable = false)
    private Instant createdAt = Instant.now();

    public enum Role { ADMIN, USER }
}
