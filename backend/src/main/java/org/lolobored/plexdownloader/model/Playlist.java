package org.lolobored.plexdownloader.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;

@Data @Entity @Table(name = "playlists")
public class Playlist {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "plex_id", unique = true, nullable = false)
    private String plexId;
    @Column(nullable = false)
    private String title;
    @Column(name = "playlist_type")
    private String playlistType;
    @Column(name = "leaf_count")
    private int leafCount;
    @Column(name = "synced_at")
    private Instant syncedAt;
}
