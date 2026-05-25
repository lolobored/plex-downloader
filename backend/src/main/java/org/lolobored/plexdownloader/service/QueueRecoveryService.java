package org.lolobored.plexdownloader.service;

import org.lolobored.plexdownloader.model.DownloadQueueItem;
import org.lolobored.plexdownloader.repository.DownloadQueueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class QueueRecoveryService {

    private final DownloadQueueRepository queueRepo;
    private final DownloadService downloadService;

    @Transactional
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        // Reset interrupted copies: IN_PROGRESS → PENDING
        List<DownloadQueueItem> interrupted = queueRepo.findByStatus(DownloadQueueItem.Status.IN_PROGRESS);
        for (DownloadQueueItem item : interrupted) {
            log.info("Queue recovery: resetting item {} '{}' from IN_PROGRESS to PENDING",
                item.getId(), item.getTitle());
            item.setStatus(DownloadQueueItem.Status.PENDING);
            queueRepo.save(item);
        }

        // Re-submit all pending items in queue-position order
        List<DownloadQueueItem> pending =
            queueRepo.findByStatusOrderByQueuePositionAsc(DownloadQueueItem.Status.PENDING);
        log.info("Queue recovery: re-submitting {} pending item(s)", pending.size());
        for (DownloadQueueItem item : pending) {
            log.info("Queue recovery: submitting item {} '{}'", item.getId(), item.getTitle());
            downloadService.executeCopyAsync(item.getId());
        }
    }
}
