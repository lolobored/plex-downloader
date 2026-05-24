package org.lolobored.plexdownloader.client;

import org.lolobored.plexdownloader.model.DownloadQueueItem;
import org.lolobored.plexdownloader.service.SettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TdarrClientTest {

    @Mock SettingsService settings;
    @Spy @InjectMocks TdarrClient client;

    @Test
    void getFileStatus_returnsEmpty_whenUrlBlank() {
        when(settings.get("tdarr.server.url")).thenReturn(Optional.of("  "));
        assertThat(client.getFileStatus("/some/file.mkv")).isEmpty();
    }

    @Test
    void getFileStatus_returnsEmpty_whenUrlMissing() {
        when(settings.get("tdarr.server.url")).thenReturn(Optional.empty());
        assertThat(client.getFileStatus("/some/file.mkv")).isEmpty();
    }

    @Test
    void getFileStatus_returnsProcessing_whenQueued() {
        when(settings.get("tdarr.server.url")).thenReturn(Optional.of("http://tdarr:8265"));
        TdarrClient.TdarrFileResponse resp = new TdarrClient.TdarrFileResponse();
        resp.setTdarrStatus("Queued");
        doReturn(resp).when(client).fetchStatus(anyString(), anyString());

        Optional<TdarrClient.TdarrFileStatus> result = client.getFileStatus("/file.mkv");

        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(DownloadQueueItem.TdarrStatus.PROCESSING);
    }

    @Test
    void getFileStatus_returnsProcessing_whenProcessing() {
        when(settings.get("tdarr.server.url")).thenReturn(Optional.of("http://tdarr:8265"));
        TdarrClient.TdarrFileResponse resp = new TdarrClient.TdarrFileResponse();
        resp.setTdarrStatus("Processing");
        doReturn(resp).when(client).fetchStatus(anyString(), anyString());

        assertThat(client.getFileStatus("/file.mkv").get().status())
            .isEqualTo(DownloadQueueItem.TdarrStatus.PROCESSING);
    }

    @Test
    void getFileStatus_returnsTranscoded_whenDoneTranscoding() {
        when(settings.get("tdarr.server.url")).thenReturn(Optional.of("http://tdarr:8265"));
        TdarrClient.TdarrFileResponse resp = new TdarrClient.TdarrFileResponse();
        resp.setTdarrStatus("Done transcoding");
        doReturn(resp).when(client).fetchStatus(anyString(), anyString());

        assertThat(client.getFileStatus("/file.mkv").get().status())
            .isEqualTo(DownloadQueueItem.TdarrStatus.TRANSCODED);
    }

    @Test
    void getFileStatus_returnsTranscoded_whenNoActionNeeded() {
        when(settings.get("tdarr.server.url")).thenReturn(Optional.of("http://tdarr:8265"));
        TdarrClient.TdarrFileResponse resp = new TdarrClient.TdarrFileResponse();
        resp.setTdarrStatus("No action needed");
        doReturn(resp).when(client).fetchStatus(anyString(), anyString());

        assertThat(client.getFileStatus("/file.mkv").get().status())
            .isEqualTo(DownloadQueueItem.TdarrStatus.TRANSCODED);
    }

    @Test
    void getFileStatus_returnsTdarrError_withErrorMessage() {
        when(settings.get("tdarr.server.url")).thenReturn(Optional.of("http://tdarr:8265"));
        TdarrClient.TdarrFileResponse resp = new TdarrClient.TdarrFileResponse();
        resp.setTdarrStatus("Transcode error");
        resp.setErrorMessage("codec not supported");
        doReturn(resp).when(client).fetchStatus(anyString(), anyString());

        Optional<TdarrClient.TdarrFileStatus> result = client.getFileStatus("/file.mkv");

        assertThat(result.get().status()).isEqualTo(DownloadQueueItem.TdarrStatus.TDARR_ERROR);
        assertThat(result.get().errorMessage()).isEqualTo("codec not supported");
    }

    @Test
    void getFileStatus_returnsEmpty_whenRestClientException() {
        when(settings.get("tdarr.server.url")).thenReturn(Optional.of("http://tdarr:8265"));
        doThrow(new RestClientException("connection refused"))
            .when(client).fetchStatus(anyString(), anyString());

        assertThat(client.getFileStatus("/file.mkv")).isEmpty();
    }

    @Test
    void getFileStatus_returnsNone_whenResponseNull() {
        when(settings.get("tdarr.server.url")).thenReturn(Optional.of("http://tdarr:8265"));
        doReturn(null).when(client).fetchStatus(anyString(), anyString());

        assertThat(client.getFileStatus("/file.mkv").get().status())
            .isEqualTo(DownloadQueueItem.TdarrStatus.NONE);
    }
}
