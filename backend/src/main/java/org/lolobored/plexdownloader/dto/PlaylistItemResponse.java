package org.lolobored.plexdownloader.dto;

public record PlaylistItemResponse(
    Long id, String plexId, String mediaType, Integer ordinal,
    String title, Integer year, String queueStatus,
    Long mediaId, Long showId, Long seasonId
) {}
