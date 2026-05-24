package org.lolobored.plexdownloader.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data @Entity @Table(name = "tv_shows")
public class TvShow {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "plex_id", unique = true, nullable = false)
    private String plexId;
    @Column(nullable = false)
    private String title;
    private Integer year;
    @Column(columnDefinition = "TEXT")
    private String summary;
    @Column(name = "poster_url", columnDefinition = "TEXT")
    private String posterUrl;
    @Column(name = "synced_at")
    private Instant syncedAt;
    @Column(name = "tmdb_id")
    private Long tmdbId;
    @Column(name = "tvdb_id")
    private Long tvdbId;
    private Float rating;
    @Column(name = "total_seasons")
    private Integer totalSeasons;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "show_genres", joinColumns = @JoinColumn(name = "show_id"))
    @Column(name = "genre")
    private List<String> genres = new ArrayList<>();

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "show_actors",
        joinColumns = @JoinColumn(name = "show_id"),
        inverseJoinColumns = @JoinColumn(name = "actor_id"))
    private List<Actor> actors = new ArrayList<>();
}
