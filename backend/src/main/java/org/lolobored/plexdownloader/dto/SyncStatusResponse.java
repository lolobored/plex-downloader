package org.lolobored.plexdownloader.dto;

import java.time.Instant;

public record SyncStatusResponse(String state, Instant lastSyncAt, int itemsSynced, String error) {}
