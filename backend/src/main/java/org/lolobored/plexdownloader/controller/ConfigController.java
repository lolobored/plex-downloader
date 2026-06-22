package org.lolobored.plexdownloader.controller;

import org.lolobored.plexdownloader.service.SettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ConfigController {

    private final SettingsService settingsService;

    @GetMapping("/output-status")
    public Map<String, Boolean> outputStatus() {
        String moviesDir  = settingsService.get("output.movies.dir").orElse("");
        String tvshowsDir = settingsService.get("output.tvshows.dir").orElse("");
        boolean configured = !moviesDir.isBlank() && !tvshowsDir.isBlank();
        return Map.of("configured", configured);
    }
}
