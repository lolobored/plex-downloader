package org.lolobored.plexdownloader.controller;

import org.lolobored.plexdownloader.service.SettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@RestController
@RequestMapping("/api/posters")
@RequiredArgsConstructor
public class PosterController {

    private static final long MAX_POSTER_BYTES = 10 * 1024 * 1024; // 10 MB

    private final SettingsService settings;

    @GetMapping("/{ratingKey}.jpg")
    public ResponseEntity<byte[]> getPoster(@PathVariable String ratingKey) {
        Path posterDir = Path.of(settings.get("plex.poster.dir").filter(s -> !s.isBlank()).orElse("/posters")).normalize();
        Path poster = posterDir.resolve(ratingKey + ".jpg").normalize();

        if (!poster.startsWith(posterDir)) {
            return ResponseEntity.badRequest().build();
        }

        if (!Files.exists(poster)) {
            return ResponseEntity.notFound().build();
        }

        try {
            if (Files.size(poster) > MAX_POSTER_BYTES) {
                log.warn("Poster file too large, skipping: {}", poster);
                return ResponseEntity.status(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR).build();
            }
            byte[] bytes = Files.readAllBytes(poster);
            return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofHours(24)))
                .body(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
