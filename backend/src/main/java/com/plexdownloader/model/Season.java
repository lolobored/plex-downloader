package com.plexdownloader.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;

@Data @Entity @Table(name = "seasons")
public class Season {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "plex_id", unique = true, nullable = false)
    private String plexId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "show_id", nullable = false)
    private TvShow show;
    @Column(name = "season_number", nullable = false)
    private Integer seasonNumber;
    private String title;
    @Column(name = "poster_url", columnDefinition = "TEXT")
    private String posterUrl;
    @Column(name = "episode_count")
    private Integer episodeCount;
    @Column(name = "synced_at")
    private Instant syncedAt;
}
