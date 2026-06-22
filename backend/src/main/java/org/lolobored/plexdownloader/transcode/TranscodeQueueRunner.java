package org.lolobored.plexdownloader.transcode;

import org.lolobored.plexdownloader.model.DownloadQueueItem;
import org.lolobored.plexdownloader.repository.DownloadQueueRepository;
import org.lolobored.plexdownloader.service.SettingsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

@Slf4j
@Component
public class TranscodeQueueRunner {

    private final DownloadQueueRepository queueRepo;
    private final TranscodeService transcodeService;
    private final Semaphore permits;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "transcode-worker");
        t.setDaemon(true);
        return t;
    });

    public TranscodeQueueRunner(DownloadQueueRepository queueRepo,
                                TranscodeService transcodeService,
                                SettingsService settings) {
        this.queueRepo = queueRepo;
        this.transcodeService = transcodeService;
        int max = parseMax(settings.get("transcode.max.concurrent").orElse("2"));
        this.permits = new Semaphore(max);
        log.info("Transcode worker: max concurrent = {}", max);
    }

    private int parseMax(String v) {
        try { return Math.max(1, Integer.parseInt(v.trim())); }
        catch (NumberFormatException e) { return 2; }
    }

    @EventListener
    public void onRequested(TranscodeRequestedEvent e) {
        submit(e.itemId());
    }

    public void submit(Long itemId) {
        pool.submit(() -> {
            try {
                permits.acquire();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                transcodeService.transcode(itemId);
            } catch (Exception ex) {
                log.error("Transcode worker error for item {}: {}", itemId, ex.getMessage(), ex);
            } finally {
                permits.release();
            }
        });
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        for (DownloadQueueItem stuck : queueRepo.findByStatus(DownloadQueueItem.Status.TRANSCODING)) {
            stuck.setStatus(DownloadQueueItem.Status.QUEUED);
            stuck.setProgressPercent(null);
            queueRepo.save(stuck);
            log.info("Recovered interrupted transcode: item={}", stuck.getId());
        }
        for (DownloadQueueItem q : queueRepo.findByStatusOrderByQueuePositionAsc(DownloadQueueItem.Status.QUEUED)) {
            submit(q.getId());
        }
    }
}
