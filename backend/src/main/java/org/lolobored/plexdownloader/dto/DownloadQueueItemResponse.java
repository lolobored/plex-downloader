package org.lolobored.plexdownloader.dto;

import org.lolobored.plexdownloader.model.DownloadQueueItem;
import java.time.Instant;

public record DownloadQueueItemResponse(
    Long id,
    DownloadQueueItem.MediaType mediaType,
    Long mediaId,
    DownloadQueueItem.Status status,
    DownloadQueueItem.TdarrStatus tdarrStatus,
    String tdarrError,
    String title,
    Integer queuePosition,
    String errorMessage,
    Instant requestedAt,
    Instant completedAt,
    Long showId,
    Long seasonId
) {
    public static DownloadQueueItemResponse from(DownloadQueueItem item, Long showId, Long seasonId) {
        return new DownloadQueueItemResponse(
            item.getId(), item.getMediaType(), item.getMediaId(),
            item.getStatus(), item.getTdarrStatus(), item.getTdarrError(),
            item.getTitle(), item.getQueuePosition(), item.getErrorMessage(),
            item.getRequestedAt(), item.getCompletedAt(),
            showId, seasonId
        );
    }
}
