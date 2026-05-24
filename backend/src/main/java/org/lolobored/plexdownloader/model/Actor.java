package org.lolobored.plexdownloader.model;

import jakarta.persistence.*;
import lombok.Data;

@Data @Entity @Table(name = "actors")
public class Actor {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "plex_id", unique = true, nullable = false)
    private String plexId;
    @Column(nullable = false)
    private String name;
    @Column(name = "photo_url", columnDefinition = "TEXT")
    private String photoUrl;
}
