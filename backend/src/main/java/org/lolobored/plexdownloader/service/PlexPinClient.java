package org.lolobored.plexdownloader.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.lolobored.plexdownloader.dto.PlexPinInitResponse;
import org.lolobored.plexdownloader.dto.PlexUserInfo;
import lombok.Data;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Objects;

@Component
public class PlexPinClient {

    private static final String PLEX_TV       = "https://plex.tv";
    private static final String CLIENT_ID     = "plex-downloader-app";
    private static final String PRODUCT       = "PlexDownloader";

    private final RestClient restClient = RestClient.builder()
        .baseUrl(PLEX_TV)
        .defaultHeader("X-Plex-Client-Identifier", CLIENT_ID)
        .defaultHeader("X-Plex-Product", PRODUCT)
        .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
        .build();

    public PlexPinInitResponse createPin() {
        PlexPinApiResponse resp = restClient.post()
            .uri("/api/v2/pins?strong=true")
            .retrieve()
            .body(PlexPinApiResponse.class);
        Objects.requireNonNull(resp, "plex.tv returned null body for createPin");
        return new PlexPinInitResponse(resp.getId(), resp.getCode());
    }

    /** Returns authToken string, or null if user hasn't authorized yet. */
    public String pollPin(Long pinId) {
        PlexPinApiResponse resp = restClient.get()
            .uri("/api/v2/pins/{id}", pinId)
            .retrieve()
            .body(PlexPinApiResponse.class);
        return (resp != null) ? resp.getAuthToken() : null;
    }

    public PlexUserInfo getUserInfo(String authToken) {
        PlexUserApiResponse resp = restClient.get()
            .uri("/api/v2/user")
            .header("X-Plex-Token", authToken)
            .retrieve()
            .body(PlexUserApiResponse.class);
        Objects.requireNonNull(resp, "plex.tv returned null body for getUserInfo");
        return new PlexUserInfo(String.valueOf(resp.getId()), resp.getUsername(), resp.getThumb());
    }

    public String buildAuthUrl(String code) {
        return "https://app.plex.tv/auth#?clientID=" + CLIENT_ID
            + "&code=" + code
            + "&context[device][product]=" + PRODUCT;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class PlexPinApiResponse {
        private Long id;
        private String code;
        @JsonProperty("authToken")
        private String authToken;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class PlexUserApiResponse {
        private Long id;
        private String username;
        private String thumb;
    }
}
