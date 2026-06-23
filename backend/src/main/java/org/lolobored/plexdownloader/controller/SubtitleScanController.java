package org.lolobored.plexdownloader.controller;

import org.lolobored.plexdownloader.dto.SubtitleScanStatus;
import org.lolobored.plexdownloader.service.SubtitleScanScheduler;
import org.lolobored.plexdownloader.service.SubtitleScanService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/subtitles")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class SubtitleScanController {

    private final SubtitleScanService scanService;
    private final SubtitleScanScheduler scheduler;

    @PostMapping("/scan")
    public ResponseEntity<Void> scan(@RequestParam(defaultValue = "false") boolean force) {
        if (scanService.isRunning()) return ResponseEntity.status(HttpStatus.CONFLICT).build();
        scheduler.triggerManual(force);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/scan/status")
    public SubtitleScanStatus status() {
        return scanService.status();
    }
}
