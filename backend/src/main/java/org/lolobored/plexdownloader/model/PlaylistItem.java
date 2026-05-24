package org.lolobored.plexdownloader.model;

import jakarta.persistence.*;
import lombok.Data;

@Data @Entity @Table(name = "playlist_items")
public class PlaylistItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "playlist_id", nullable = false)
    private Long playlistId;
    @Column(name = "plex_id", nullable = false)
    private String plexId;
    @Column(name = "media_type", nullable = false)
    private String mediaType;  // "MOVIE" or "EPISODE"
    private Integer ordinal;
}
