package com.plexdownloader.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.plexdownloader.client.dto.PlexItem;
import com.plexdownloader.client.dto.PlexLibrary;
import com.plexdownloader.client.dto.PlexLibraryPage;
import com.plexdownloader.repository.UserRepository;
import com.plexdownloader.service.SettingsService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Component
@RequiredArgsConstructor
public class PlexMediaServerClient {

    private static final int PAGE_SIZE = 50;

    private final SettingsService settings;
    private final UserRepository userRepository;

    private RestClient buildClient() {
        String baseUrl = settings.getRequired("plex.server.url");
        String token = userRepository.findById(1L)
            .map(u -> u.getPlexToken())
            .orElseThrow(() -> new IllegalStateException("No admin user found for Plex token"));

        return RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("X-Plex-Token", token)
            .defaultHeader("X-Plex-Client-Identifier", "plex-downloader-app")
            .defaultHeader("X-Plex-Product", "PlexDownloader")
            .defaultHeader("Accept", "application/json")
            .build();
    }

    public List<PlexLibrary> getLibraries() {
        PlexLibrariesResponse resp = buildClient().get()
            .uri("/library/sections")
            .retrieve()
            .body(PlexLibrariesResponse.class);
        if (resp == null || resp.getMediaContainer() == null) return List.of();
        List<PlexLibrary> dirs = resp.getMediaContainer().getDirectory();
        return dirs != null ? dirs : List.of();
    }

    public PlexLibraryPage getLibraryContents(String libraryKey, int offset) {
        PlexLibraryContentsResponse resp = buildClient().get()
            .uri(u -> u.path("/library/sections/{key}/all")
                .queryParam("includeGuids", "1")
                .build(libraryKey))
            .header("X-Plex-Container-Start", String.valueOf(offset))
            .header("X-Plex-Container-Size", String.valueOf(PAGE_SIZE))
            .retrieve()
            .body(PlexLibraryContentsResponse.class);
        if (resp == null || resp.getMediaContainer() == null) return new PlexLibraryPage(0, List.of());
        var mc = resp.getMediaContainer();
        List<PlexItem> items = mc.getMetadata() != null ? mc.getMetadata() : List.of();
        return new PlexLibraryPage(mc.getTotalSize(), items);
    }

    public PlexItem getItemDetail(String ratingKey) {
        PlexLibraryContentsResponse resp = buildClient().get()
            .uri("/library/metadata/{key}", ratingKey)
            .retrieve()
            .body(PlexLibraryContentsResponse.class);
        if (resp == null || resp.getMediaContainer() == null
                || resp.getMediaContainer().getMetadata() == null
                || resp.getMediaContainer().getMetadata().isEmpty()) {
            throw new IllegalStateException("No metadata returned for ratingKey: " + ratingKey);
        }
        return resp.getMediaContainer().getMetadata().get(0);
    }

    public List<PlexItem> getChildren(String ratingKey) {
        PlexLibraryContentsResponse resp = buildClient().get()
            .uri("/library/metadata/{key}/children", ratingKey)
            .retrieve()
            .body(PlexLibraryContentsResponse.class);
        if (resp == null || resp.getMediaContainer() == null) return List.of();
        List<PlexItem> items = resp.getMediaContainer().getMetadata();
        return items != null ? items : List.of();
    }

    public void downloadThumb(String thumbPath, Path destination) {
        byte[] bytes = buildClient().get()
            .uri(thumbPath)
            .accept(MediaType.IMAGE_JPEG)
            .retrieve()
            .body(byte[].class);
        if (bytes != null && bytes.length > 0) {
            try {
                Files.createDirectories(destination.getParent());
                Files.write(destination, bytes);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to write poster to " + destination, e);
            }
        }
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class PlexLibrariesResponse {
        @JsonProperty("MediaContainer")
        private Container mediaContainer;

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        static class Container {
            @JsonProperty("Directory")
            private List<PlexLibrary> directory;
        }
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class PlexLibraryContentsResponse {
        @JsonProperty("MediaContainer")
        private Container mediaContainer;

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        static class Container {
            private int totalSize;
            @JsonProperty("Metadata")
            private List<PlexItem> metadata;
        }
    }
}
