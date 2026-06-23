package org.lolobored.plexdownloader.dto;

import org.lolobored.plexdownloader.model.DownloadQueueItem;
import java.time.Instant;

public record DownloadQueueItemResponse(
    Long id,
    DownloadQueueItem.MediaType mediaType,
    Long mediaId,
    DownloadQueueItem.Status status,
    Integer progressPercent,
    String transcodeError,
    String qualityProfileName,
    String title,
    Integer queuePosition,
    String errorMessage,
    Instant requestedAt,
    Instant completedAt,
    Long showId,
    Long seasonId,
    Long playlistId,
    String playlistTitle,
    String showTitle,
    Integer seasonNumber,
    Double compressionRatio,
    Long sourceSizeBytes,
    Long outputSizeBytes,
    Instant transcodeStartedAt,
    String sourceSubtitleLangs,
    Boolean sourceSubtitlesScanned,
    String outputSubtitleLangs,
    Boolean outputSubtitlesScanned
) {
    public static DownloadQueueItemResponse from(
            DownloadQueueItem item,
            Long showId, Long seasonId,
            Long playlistId, String playlistTitle,
            String showTitle, Integer seasonNumber,
            String sourceSubtitleLangs, Boolean sourceSubtitlesScanned) {
        return new DownloadQueueItemResponse(
            item.getId(), item.getMediaType(), item.getMediaId(),
            item.getStatus(), item.getProgressPercent(), item.getTranscodeError(),
            item.getQualityProfile() != null ? item.getQualityProfile().getName() : null,
            item.getTitle(), item.getQueuePosition(), item.getErrorMessage(),
            item.getRequestedAt(), item.getCompletedAt(),
            showId, seasonId,
            playlistId, playlistTitle,
            showTitle, seasonNumber,
            item.getCompressionRatio(),
            item.getSourceSizeBytes(),
            item.getOutputSizeBytes(),
            item.getTranscodeStartedAt(),
            sourceSubtitleLangs,
            sourceSubtitlesScanned,
            item.getOutputSubtitleLangs(),
            item.getOutputSubtitlesScannedAt() != null
        );
    }
}
