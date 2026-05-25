package org.lolobored.plexdownloader.service;

import org.lolobored.plexdownloader.model.DownloadQueueItem;
import org.lolobored.plexdownloader.repository.DownloadQueueRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QueueRecoveryServiceTest {

    @Mock DownloadQueueRepository queueRepo;
    @Mock DownloadService downloadService;
    @InjectMocks QueueRecoveryService service;

    private DownloadQueueItem item(Long id, DownloadQueueItem.Status status) {
        DownloadQueueItem i = new DownloadQueueItem();
        i.setId(id);
        i.setStatus(status);
        i.setTitle("Item " + id);
        i.setQueuePosition(id.intValue());
        return i;
    }

    @Test
    void onReady_resubmitsPendingItems() {
        DownloadQueueItem p1 = item(1L, DownloadQueueItem.Status.PENDING);
        DownloadQueueItem p2 = item(2L, DownloadQueueItem.Status.PENDING);
        when(queueRepo.findByStatus(DownloadQueueItem.Status.IN_PROGRESS)).thenReturn(List.of());
        when(queueRepo.findByStatusOrderByQueuePositionAsc(DownloadQueueItem.Status.PENDING))
            .thenReturn(List.of(p1, p2));

        service.onApplicationReady();

        verify(downloadService).executeCopyAsync(1L);
        verify(downloadService).executeCopyAsync(2L);
        verify(queueRepo, never()).save(any());
    }

    @Test
    void onReady_resetsInProgressToPendingAndResubmits() {
        DownloadQueueItem inProgress = item(3L, DownloadQueueItem.Status.IN_PROGRESS);
        when(queueRepo.findByStatus(DownloadQueueItem.Status.IN_PROGRESS)).thenReturn(List.of(inProgress));
        when(queueRepo.findByStatusOrderByQueuePositionAsc(DownloadQueueItem.Status.PENDING))
            .thenReturn(List.of(inProgress));

        service.onApplicationReady();

        assertThat(inProgress.getStatus()).isEqualTo(DownloadQueueItem.Status.PENDING);
        verify(queueRepo).save(inProgress);
        verify(downloadService).executeCopyAsync(3L);
    }

    @Test
    void onReady_skipsDoneAndError() {
        when(queueRepo.findByStatus(DownloadQueueItem.Status.IN_PROGRESS)).thenReturn(List.of());
        when(queueRepo.findByStatusOrderByQueuePositionAsc(DownloadQueueItem.Status.PENDING))
            .thenReturn(List.of());

        service.onApplicationReady();

        verify(downloadService, never()).executeCopyAsync(any());
    }

    @Test
    void onReady_respectsQueuePositionOrder() {
        DownloadQueueItem p1 = item(1L, DownloadQueueItem.Status.PENDING);
        DownloadQueueItem p2 = item(2L, DownloadQueueItem.Status.PENDING);
        DownloadQueueItem p3 = item(3L, DownloadQueueItem.Status.PENDING);
        when(queueRepo.findByStatus(DownloadQueueItem.Status.IN_PROGRESS)).thenReturn(List.of());
        when(queueRepo.findByStatusOrderByQueuePositionAsc(DownloadQueueItem.Status.PENDING))
            .thenReturn(List.of(p1, p2, p3));

        service.onApplicationReady();

        InOrder inOrder = Mockito.inOrder(downloadService);
        inOrder.verify(downloadService).executeCopyAsync(1L);
        inOrder.verify(downloadService).executeCopyAsync(2L);
        inOrder.verify(downloadService).executeCopyAsync(3L);
    }
}
