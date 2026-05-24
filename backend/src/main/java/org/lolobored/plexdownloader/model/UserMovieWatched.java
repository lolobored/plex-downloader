package org.lolobored.plexdownloader.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;

@Data @Entity @Table(name = "user_movie_watched")
public class UserMovieWatched {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "movie_id", nullable = false)
    private Movie movie;

    @Column(name = "watched_at")
    private Instant watchedAt;

    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt;
}
