package org.lolobored.plexdownloader.client.dto;

import java.util.List;

public record PlexLibraryPage(int totalSize, List<PlexItem> items) {}
