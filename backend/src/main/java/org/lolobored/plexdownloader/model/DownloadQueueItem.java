package org.lolobored.plexdownloader.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.ToString;
import java.time.Instant;

@Data @ToString(exclude = "user") @Entity @Table(name = "download_queue")
public class DownloadQueueItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    @Enumerated(EnumType.STRING)
    @Column(name = "media_type", nullable = false)
    private MediaType mediaType;
    @Column(name = "media_id", nullable = false)
    private Long mediaId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.QUEUED;

    @Column(name = "progress_percent")
    private Integer progressPercent;

    @Column(name = "transcode_error", columnDefinition = "TEXT")
    private String transcodeError;

    @Column(name = "transcode_started_at")
    private Instant transcodeStartedAt;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quality_profile_id")
    private QualityProfile qualityProfile;

    @Column(name = "title", columnDefinition = "TEXT")
    private String title;
    @Column(name = "queue_position")
    private Integer queuePosition;
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
    @Column(name = "source_file_path", columnDefinition = "TEXT")
    private String sourceFilePath;
    @Column(name = "dest_file_path", columnDefinition = "TEXT")
    private String destFilePath;
    @Column(name = "requested_at", updatable = false)
    private Instant requestedAt = Instant.now();
    @Column(name = "completed_at")
    private Instant completedAt;
    @Column(name = "playlist_id")
    private Long playlistId;

    public enum MediaType { MOVIE, EPISODE }
    public enum Status { QUEUED, TRANSCODING, DONE, ERROR }
}
