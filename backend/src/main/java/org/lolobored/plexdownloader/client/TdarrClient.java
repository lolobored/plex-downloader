package org.lolobored.plexdownloader.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.lolobored.plexdownloader.model.DownloadQueueItem;
import org.lolobored.plexdownloader.service.SettingsService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class TdarrClient {

    private final SettingsService settings;

    /** HTTP/1.1-only client — Tdarr does not support HTTP/2. */
    private static final RestClient HTTP_CLIENT = RestClient.builder()
        .requestFactory(new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build()))
        .build();

    public record TdarrFileStatus(
        DownloadQueueItem.TdarrStatus status,
        String errorMessage,
        String outputFilePath) {}

    /** Result of a connectivity test. {@code ok=true} means HTTP response received. */
    public record PingResult(boolean ok, String detail) {}


    // ---------- internal helpers ----------

    private String apiKey() {
        return settings.get("tdarr.api.key").orElse("").trim();
    }

    private RestClient.RequestBodySpec withAuth(RestClient.RequestBodySpec spec) {
        String key = apiKey();
        return key.isBlank() ? spec : spec.header("x-api-key", key);
    }

    private RestClient.RequestHeadersSpec<?> withAuth(RestClient.RequestHeadersSpec<?> spec) {
        String key = apiKey();
        return key.isBlank() ? spec : spec.header("x-api-key", key);
    }

    // ---------- public API ----------

    public Optional<TdarrFileStatus> getFileStatus(String absoluteFilePath) {
        String baseUrl = settings.get("tdarr.server.url").orElse("").trim();
        if (baseUrl.isBlank()) {
            return Optional.empty();
        }
        try {
            TdarrFileResponse response = fetchStatus(baseUrl, absoluteFilePath);
            if (response == null) {
                return Optional.of(new TdarrFileStatus(DownloadQueueItem.TdarrStatus.NONE, null, null));
            }
            DownloadQueueItem.TdarrStatus status = mapStatus(response.getHealthCheck(), response.getTranscodeDecisionMaker());
            String error = status == DownloadQueueItem.TdarrStatus.TDARR_ERROR
                ? (response.getErrors() != null ? response.getErrors() : "Unknown Tdarr error") : null;
            return Optional.of(new TdarrFileStatus(status, error, null));
        } catch (RestClientException e) {
            log.error("Tdarr API error for {}: {}", absoluteFilePath, e.getMessage());
            return Optional.empty();
        }
    }

    protected TdarrFileResponse fetchStatus(String baseUrl, String filePath) {
        Map<String, Object> body = Map.of(
            "data", Map.of(
                "collection", "FileJSONDB",
                "mode",       "getById",
                "docID",      filePath
            )
        );
        return withAuth(HTTP_CLIENT.post()
            .uri(baseUrl + "/api/v2/cruddb")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body))
            .retrieve()
            .body(TdarrFileResponse.class);
    }

    /**
     * Tests connectivity to the Tdarr server.
     * Any HTTP response (including 4xx/5xx) → ok=true (server reachable).
     * Connection failure (refused, DNS, timeout) → ok=false.
     * {@code apiKey} overrides the stored setting (pass null to use stored value).
     */
    public PingResult ping(String url, String apiKey) {
        if (url == null || url.isBlank()) return new PingResult(false, "URL is empty");
        String key = (apiKey != null && !apiKey.isBlank()) ? apiKey : this.apiKey();
        try {
            RestClient.RequestHeadersSpec<?> req = HTTP_CLIENT.get()
                .uri(url.stripTrailing() + "/api/v2/status");
            if (!key.isBlank()) req = req.header("x-api-key", key);
            req.retrieve().toBodilessEntity();
            return new PingResult(true, "Connected");
        } catch (org.springframework.web.client.RestClientResponseException e) {
            // Got HTTP response — server IS reachable (401 = need API key, etc.)
            int code = e.getStatusCode().value();
            String detail = code == 401 ? "Connected (401 — check API key)"
                          : code == 403 ? "Connected (403 — forbidden)"
                          : "Connected (HTTP " + code + ")";
            log.debug("Tdarr ping HTTP {} at {}", code, url);
            return new PingResult(true, detail);
        } catch (RestClientException e) {
            // Connection failure — host unreachable, refused, timeout, DNS failure
            log.warn("Tdarr ping connection failed for {}: {}", url, e.getMessage());
            return new PingResult(false, "Connection failed: " + e.getMessage());
        }
    }

    /** Package-private so tests can stub it with @Spy. */
    void callDelete(String baseUrl, String filePath) {
        Map<String, Object> body = Map.of(
            "data", Map.of(
                "collection", "FileJSONDB",
                "mode",       "removeOne",
                "docID",      filePath
            )
        );
        withAuth(HTTP_CLIENT.post()
            .uri(baseUrl + "/api/v2/cruddb")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body))
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
            log.error("Tdarr deleteFile failed for {}: {}", filePath, e.getMessage());
        }
    }

    /**
     * Maps Tdarr's actual API fields to our internal status.
     * HealthCheck:            "Queued" | "Processing" | "Healthy" | "HealthError"
     * TranscodeDecisionMaker: "Queued" | "Processing" | "Transcoded" | "Not required" | "TranscodeError"
     */
    private DownloadQueueItem.TdarrStatus mapStatus(String healthCheck, String transcode) {
        if ("HealthError".equals(healthCheck)) return DownloadQueueItem.TdarrStatus.TDARR_ERROR;
        if ("TranscodeError".equals(transcode))  return DownloadQueueItem.TdarrStatus.TDARR_ERROR;
        if ("Transcoded".equals(transcode) || "Not required".equals(transcode))
            return DownloadQueueItem.TdarrStatus.TRANSCODED;
        if ("Queued".equals(transcode) || "Processing".equals(transcode)
                || "Queued".equals(healthCheck) || "Processing".equals(healthCheck))
            return DownloadQueueItem.TdarrStatus.PROCESSING;
        return DownloadQueueItem.TdarrStatus.NONE;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class TdarrFileResponse {
        @JsonProperty("HealthCheck")
        private String healthCheck;
        @JsonProperty("TranscodeDecisionMaker")
        private String transcodeDecisionMaker;
        @JsonProperty("errors")
        private String errors;
    }
}
