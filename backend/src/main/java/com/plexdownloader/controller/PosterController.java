package com.plexdownloader.controller;

import com.plexdownloader.service.SettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/posters")
@RequiredArgsConstructor
public class PosterController {

    private final SettingsService settings;

    @GetMapping("/{ratingKey}.jpg")
    public ResponseEntity<byte[]> getPoster(@PathVariable String ratingKey) {
        Path posterDir = Path.of(settings.getRequired("plex.poster.dir"));
        Path poster = posterDir.resolve(ratingKey + ".jpg");

        if (!Files.exists(poster)) {
            return ResponseEntity.notFound().build();
        }

        try {
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
