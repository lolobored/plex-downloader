package com.plexdownloader.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;

@Data
@Entity
@Table(name = "user_episode_watched",
    uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "episode_id"}))
public class UserEpisodeWatched {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "episode_id", nullable = false)
    private Episode episode;

    @Column(name = "watched_at")
    private Instant watchedAt;

    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt;
}
