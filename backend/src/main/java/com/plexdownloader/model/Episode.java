package com.plexdownloader.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.ToString;
import java.time.Instant;
import java.time.LocalDate;

@Data @ToString(exclude = "season") @Entity @Table(name = "episodes")
public class Episode {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "plex_id", unique = true, nullable = false)
    private String plexId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "season_id", nullable = false)
    private Season season;
    @Column(name = "episode_number", nullable = false)
    private Integer episodeNumber;
    private String title;
    @Column(columnDefinition = "TEXT")
    private String summary;
    @Column(name = "thumbnail_url", columnDefinition = "TEXT")
    private String thumbnailUrl;
    @Column(name = "file_path", columnDefinition = "TEXT")
    private String filePath;
    @Column(name = "duration_ms")
    private Long durationMs;
    @Column(name = "air_date")
    private LocalDate airDate;
    private String director;
    private String writer;
    @Column(name = "video_resolution")
    private String videoResolution;
    @Column(name = "synced_at")
    private Instant syncedAt;
}
