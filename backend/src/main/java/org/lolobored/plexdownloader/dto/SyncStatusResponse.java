package org.lolobored.plexdownloader.dto;

import java.time.Instant;
import java.util.List;

public record SyncStatusResponse(
        String state,
        Instant lastSyncAt,
        int itemsSynced,
        int totalItems,
        List<LibraryProgress> libraries,
        String error) {}
