package org.lolobored.plexdownloader.controller;

import org.lolobored.plexdownloader.client.PlexMediaServerClient;
import org.lolobored.plexdownloader.client.dto.PlexLibrary;
import org.lolobored.plexdownloader.dto.SyncStatusResponse;
import org.lolobored.plexdownloader.service.LibrarySyncScheduler;
import org.lolobored.plexdownloader.service.LibrarySyncService;
import org.lolobored.plexdownloader.service.SettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final SettingsService settingsService;
    private final LibrarySyncService syncService;
    private final LibrarySyncScheduler syncScheduler;
    private final PlexMediaServerClient plexClient;

    @GetMapping("/settings")
    public Map<String, String> getSettings() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("plex.server.url",                    settingsService.get("plex.server.url").orElse(""));
        result.put("plex.path.prefix.movies.plex",       settingsService.get("plex.path.prefix.movies.plex").orElse(""));
        result.put("plex.path.prefix.movies.app",        settingsService.get("plex.path.prefix.movies.app").orElse(""));
        result.put("plex.path.prefix.tv.plex",           settingsService.get("plex.path.prefix.tv.plex").orElse(""));
        result.put("plex.path.prefix.tv.app",            settingsService.get("plex.path.prefix.tv.app").orElse(""));
        result.put("plex.conversion.dir",                settingsService.get("plex.conversion.dir").orElse(""));
        result.put("plex.sync.cron",                     settingsService.get("plex.sync.cron").orElse("0 0 */6 * * *"));
        result.put("plex.sync.libraries",                settingsService.get("plex.sync.libraries").orElse(""));
        result.put("tdarr.server.url",                   settingsService.get("tdarr.server.url").orElse(""));
        result.put("tdarr.path.prefix.conversion",       settingsService.get("tdarr.path.prefix.conversion").orElse(""));
        result.put("tdarr.sync.cron",                    settingsService.get("tdarr.sync.cron").orElse("0 */30 * * * *"));
        return result;
    }

    @GetMapping("/plex/libraries")
    public List<PlexLibrary> getPlexLibraries(
            @RequestParam(required = false) String url) {
        return url != null && !url.isBlank()
            ? plexClient.getLibraries(url)
            : plexClient.getLibraries();
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
