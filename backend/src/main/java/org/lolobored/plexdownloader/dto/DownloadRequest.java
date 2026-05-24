package org.lolobored.plexdownloader.dto;

public record DownloadRequest(String type, Long id) {
    // type: MOVIE | SHOW | SEASON | EPISODE
}
