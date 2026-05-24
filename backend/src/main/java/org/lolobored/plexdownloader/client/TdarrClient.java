package org.lolobored.plexdownloader.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.lolobored.plexdownloader.model.DownloadQueueItem;
import org.lolobored.plexdownloader.service.SettingsService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class TdarrClient {

    private final SettingsService settings;

    public record TdarrFileStatus(DownloadQueueItem.TdarrStatus status, String errorMessage) {}

    public Optional<TdarrFileStatus> getFileStatus(String absoluteFilePath) {
        String baseUrl = settings.get("tdarr.server.url").orElse("").trim();
        if (baseUrl.isBlank()) {
            return Optional.empty();
        }
        try {
            TdarrFileResponse response = fetchStatus(baseUrl, absoluteFilePath);
            if (response == null) {
                return Optional.of(new TdarrFileStatus(DownloadQueueItem.TdarrStatus.NONE, null));
            }
            DownloadQueueItem.TdarrStatus status = mapStatus(response.getTdarrStatus());
            String error = status == DownloadQueueItem.TdarrStatus.TDARR_ERROR
                ? response.getErrorMessage() : null;
            return Optional.of(new TdarrFileStatus(status, error));
        } catch (RestClientException e) {
            log.warn("Tdarr API error for {}: {}", absoluteFilePath, e.getMessage());
            return Optional.empty();
        }
    }

    protected TdarrFileResponse fetchStatus(String baseUrl, String filePath) {
        Map<String, Object> body = Map.of(
            "collection", "FileJSONDB",
            "mode",       "getByID",
            "docID",      filePath
        );
        return RestClient.create().post()
            .uri(baseUrl + "/api/v2/cruddb")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(TdarrFileResponse.class);
    }

    private DownloadQueueItem.TdarrStatus mapStatus(String tdarrStatus) {
        if (tdarrStatus == null || tdarrStatus.isBlank()) {
            return DownloadQueueItem.TdarrStatus.NONE;
        }
        return switch (tdarrStatus) {
            case "Queued", "Processing" -> DownloadQueueItem.TdarrStatus.PROCESSING;
            case "Done transcoding", "No action needed" -> DownloadQueueItem.TdarrStatus.TRANSCODED;
            case "Transcode error", "Health error" -> DownloadQueueItem.TdarrStatus.TDARR_ERROR;
            default -> DownloadQueueItem.TdarrStatus.NONE;
        };
    }

    /** Package-private so tests can stub it with @Spy. */
    void callDelete(String baseUrl, String filePath) {
        Map<String, Object> body = Map.of(
            "collection", "FileJSONDB",
            "mode",       "deleteOne",
            "docID",      filePath
        );
        RestClient.create().post()
            .uri(baseUrl + "/api/v2/cruddb")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toBodilessEntity();
    }

    public void deleteFile(String filePath) {
        String baseUrl = settings.get("tdarr.server.url").orElse("").trim();
        if (baseUrl.isBlank()) {
            log.warn("Tdarr URL not configured, skipping deleteFile for {}", filePath);
            return;
        }
        try {
            callDelete(baseUrl, filePath);
        } catch (RestClientException e) {
            log.warn("Tdarr deleteFile failed for {}: {}", filePath, e.getMessage());
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class TdarrFileResponse {
        @JsonProperty("tdarrStatus")
        private String tdarrStatus;
        @JsonProperty("errorMessage")
        private String errorMessage;
    }
}
