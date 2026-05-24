package org.lolobored.plexdownloader.dto;

public record LibraryProgress(String key, String title, int itemsSynced, int totalItems, boolean done) {}
