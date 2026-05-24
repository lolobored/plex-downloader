package org.lolobored.plexdownloader.service;

import org.lolobored.plexdownloader.client.TdarrClient;
import org.lolobored.plexdownloader.model.DownloadQueueItem;
import org.lolobored.plexdownloader.repository.DownloadQueueRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TdarrSyncSchedulerTest {

    @Mock TdarrClient tdarrClient;
    @Mock DownloadQueueRepository queueRepo;
    @Mock SettingsService settings;
    @InjectMocks TdarrSyncScheduler scheduler;

    private DownloadQueueItem doneItem(String destPath) {
        DownloadQueueItem item = new DownloadQueueItem();
        item.setId(1L);
        item.setDestFilePath(destPath);
        item.setStatus(DownloadQueueItem.Status.DONE);
        item.setTdarrStatus(DownloadQueueItem.TdarrStatus.NONE);
        return item;
    }

    @Test
    void syncAll_skipsItem_whenTdarrReturnsEmpty() {
        DownloadQueueItem item = doneItem("/conv/movies/test/movie.mkv");
        when(queueRepo.findByStatusAndTdarrStatusNotIn(any(), any())).thenReturn(List.of(item));
        when(tdarrClient.getFileStatus(anyString())).thenReturn(Optional.empty());

        scheduler.syncAll();

        verify(queueRepo, never()).save(any());
    }

    @Test
    void syncAll_updatesStatusToProcessing() {
        DownloadQueueItem item = doneItem("/conv/movies/test/movie.mkv");
        when(queueRepo.findByStatusAndTdarrStatusNotIn(any(), any())).thenReturn(List.of(item));
        when(tdarrClient.getFileStatus("/conv/movies/test/movie.mkv"))
            .thenReturn(Optional.of(new TdarrClient.TdarrFileStatus(
                DownloadQueueItem.TdarrStatus.PROCESSING, null)));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        scheduler.syncAll();

        verify(queueRepo).save(argThat(i ->
            i.getTdarrStatus() == DownloadQueueItem.TdarrStatus.PROCESSING &&
            i.getTdarrError() == null
        ));
    }

    @Test
    void syncAll_updatesStatusToTranscoded() {
        DownloadQueueItem item = doneItem("/conv/movies/test/movie.mkv");
        when(queueRepo.findByStatusAndTdarrStatusNotIn(any(), any())).thenReturn(List.of(item));
        when(tdarrClient.getFileStatus(anyString()))
            .thenReturn(Optional.of(new TdarrClient.TdarrFileStatus(
                DownloadQueueItem.TdarrStatus.TRANSCODED, null)));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        scheduler.syncAll();

        verify(queueRepo).save(argThat(i ->
            i.getTdarrStatus() == DownloadQueueItem.TdarrStatus.TRANSCODED
        ));
    }

    @Test
    void syncAll_updatesStatusToError_withMessage() {
        DownloadQueueItem item = doneItem("/conv/movies/test/movie.mkv");
        when(queueRepo.findByStatusAndTdarrStatusNotIn(any(), any())).thenReturn(List.of(item));
        when(tdarrClient.getFileStatus(anyString()))
            .thenReturn(Optional.of(new TdarrClient.TdarrFileStatus(
                DownloadQueueItem.TdarrStatus.TDARR_ERROR, "codec not supported")));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        scheduler.syncAll();

        verify(queueRepo).save(argThat(i ->
            i.getTdarrStatus() == DownloadQueueItem.TdarrStatus.TDARR_ERROR &&
            "codec not supported".equals(i.getTdarrError())
        ));
    }

    @Test
    void syncAll_doesNothingWhenNoItemsPending() {
        when(queueRepo.findByStatusAndTdarrStatusNotIn(any(), any())).thenReturn(List.of());

        scheduler.syncAll();

        verify(tdarrClient, never()).getFileStatus(any());
        verify(queueRepo, never()).save(any());
    }

    @Test
    void syncAll_queriesCorrectStatuses() {
        when(queueRepo.findByStatusAndTdarrStatusNotIn(any(), any())).thenReturn(List.of());

        scheduler.syncAll();

        verify(queueRepo).findByStatusAndTdarrStatusNotIn(
            eq(DownloadQueueItem.Status.DONE),
            argThat((Collection<DownloadQueueItem.TdarrStatus> col) ->
                col.contains(DownloadQueueItem.TdarrStatus.TRANSCODED) &&
                col.contains(DownloadQueueItem.TdarrStatus.TDARR_ERROR)
            )
        );
    }
}
