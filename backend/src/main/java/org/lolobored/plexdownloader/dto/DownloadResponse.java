package org.lolobored.plexdownloader.dto;

import java.util.List;

public record DownloadResponse(List<Long> jobIds, String status) {}
