package com.plexdownloader.controller;

import com.plexdownloader.dto.SyncStatusResponse;
import com.plexdownloader.service.LibrarySyncScheduler;
import com.plexdownloader.service.LibrarySyncService;
import com.plexdownloader.service.SettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final SettingsService settingsService;
    private final LibrarySyncService syncService;
    private final LibrarySyncScheduler syncScheduler;

    @GetMapping("/settings")
    public Map<String, String> getSettings() {
        return Map.of(
            "plex.server.url",         settingsService.get("plex.server.url").orElse(""),
            "plex.path.prefix.plex",   settingsService.get("plex.path.prefix.plex").orElse(""),
            "plex.path.prefix.app",    settingsService.get("plex.path.prefix.app").orElse(""),
            "plex.poster.dir",         settingsService.get("plex.poster.dir").orElse(""),
            "plex.conversion.dir",     settingsService.get("plex.conversion.dir").orElse(""),
            "plex.sync.cron",          settingsService.get("plex.sync.cron").orElse("0 0 */6 * * *")
        );
    }

    @PutMapping("/settings")
    public ResponseEntity<Void> putSettings(@RequestBody Map<String, String> settings) {
        settings.forEach(settingsService::set);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/sync/status")
    public SyncStatusResponse syncStatus() {
        return syncService.status();
    }

    @PostMapping("/sync")
    public ResponseEntity<Void> triggerSync() {
        if (syncService.isRunning()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        syncScheduler.triggerManual();
        return ResponseEntity.accepted().build();
    }
}
