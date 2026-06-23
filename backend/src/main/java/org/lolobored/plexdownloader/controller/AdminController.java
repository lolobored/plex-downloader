package org.lolobored.plexdownloader.controller;

import org.lolobored.plexdownloader.client.PlexMediaServerClient;
import org.lolobored.plexdownloader.client.dto.PlexLibrary;
import org.lolobored.plexdownloader.dto.SyncStatusResponse;
import org.lolobored.plexdownloader.model.DownloadQueueItem;
import org.lolobored.plexdownloader.service.LibrarySyncScheduler;
import org.lolobored.plexdownloader.service.LibrarySyncService;
import org.lolobored.plexdownloader.service.OutputRelocationService;
import org.lolobored.plexdownloader.service.OutputRelocationService.RelocationResult;
import org.lolobored.plexdownloader.service.SettingsService;
import org.lolobored.plexdownloader.transcode.FileBrowserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private static final Set<String> OUTPUT_DIR_KEYS = Set.of("output.movies.dir", "output.tvshows.dir");

    private final SettingsService settingsService;
    private final LibrarySyncService syncService;
    private final LibrarySyncScheduler syncScheduler;
    private final PlexMediaServerClient plexClient;
    private final FileBrowserService fileBrowserService;
    private final OutputRelocationService relocationService;

    @GetMapping("/settings")
    public Map<String, String> getSettings() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("plex.server.url",          settingsService.get("plex.server.url").orElse(""));
        result.put("plex.sync.cron",           settingsService.get("plex.sync.cron").orElse("0 0 */6 * * *"));
        result.put("plex.sync.libraries",      settingsService.get("plex.sync.libraries").orElse(""));
        result.put("transcode.max.concurrent", settingsService.get("transcode.max.concurrent").orElse("2"));
        result.put("output.movies.dir",        settingsService.get("output.movies.dir").orElse(""));
        result.put("output.tvshows.dir",       settingsService.get("output.tvshows.dir").orElse(""));
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
        for (String key : OUTPUT_DIR_KEYS) {
            if (settings.containsKey(key)) {
                String dir = settings.get(key);
                if (dir != null && !dir.isBlank() && !fileBrowserService.isWritable(dir)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Invalid output directory (not writable): " + dir);
                }
            }
        }
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

    record RelocateRequest(String mediaType, String oldRoot, String newRoot) {}

    @PostMapping("/output/relocate")
    public RelocationResult relocateOutput(@RequestBody RelocateRequest req) {
        if (req.oldRoot() == null || req.oldRoot().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "oldRoot must not be blank");
        }
        if (req.newRoot() == null || req.newRoot().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "newRoot must not be blank");
        }
        if (req.mediaType() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mediaType must not be null");
        }
        DownloadQueueItem.MediaType mt;
        try {
            mt = DownloadQueueItem.MediaType.valueOf(req.mediaType());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid mediaType: " + req.mediaType());
        }
        return relocationService.relocate(mt, req.oldRoot(), req.newRoot());
    }
}
