package org.lolobored.plexdownloader.dto;

import java.util.List;

public record PlaylistDetailResponse(
    Long id,
    String plexId,
    String title,
    String playlistType,
    int leafCount,
    boolean subscribed,
    List<String> posterPlexIds,
    List<PlaylistItemResponse> items
) {}
