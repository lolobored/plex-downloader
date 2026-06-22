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

@Slf4j
@Component
public class TranscodeQueueRunner {

    private final DownloadQueueRepository queueRepo;
    private final TranscodeService transcodeService;
    private final SettingsService settings;
    private final ResizableSemaphore permits;
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
        this.settings = settings;
        int max = parseMax(settings.get("transcode.max.concurrent").orElse("2"));
        this.permits = new ResizableSemaphore(max);
        log.info("Transcode worker: max concurrent = {}", max);
    }

    public int getMaxConcurrent() {
        return permits.getMaxPermits();
    }

    public void setMaxConcurrent(int n) {
        int clamped = Math.max(1, n);
        permits.setMaxPermits(clamped);
        settings.set("transcode.max.concurrent", String.valueOf(clamped));
        log.info("Transcode worker: max concurrent changed to {}", clamped);
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
        // FETCHING items: temp dir is wiped on restart, source copy must redo from scratch
        for (DownloadQueueItem fetching : queueRepo.findByStatus(DownloadQueueItem.Status.FETCHING)) {
            fetching.setStatus(DownloadQueueItem.Status.QUEUED);
            fetching.setProgressPercent(null);
            queueRepo.save(fetching);
            log.info("Recovered interrupted fetch (re-queuing): item={}", fetching.getId());
        }
        for (DownloadQueueItem stuck : queueRepo.findByStatus(DownloadQueueItem.Status.TRANSCODING)) {
            stuck.setStatus(DownloadQueueItem.Status.QUEUED);
            stuck.setProgressPercent(null);
            queueRepo.save(stuck);
            log.info("Recovered interrupted transcode: item={}", stuck.getId());
        }
        // COPYING items also re-queue: temp dir is wiped on restart, move must re-transcode
        for (DownloadQueueItem copying : queueRepo.findByStatus(DownloadQueueItem.Status.COPYING)) {
            copying.setStatus(DownloadQueueItem.Status.QUEUED);
            copying.setProgressPercent(null);
            queueRepo.save(copying);
            log.info("Recovered interrupted copy (re-queuing transcode): item={}", copying.getId());
        }
        for (DownloadQueueItem q : queueRepo.findByStatusOrderByQueuePositionAsc(DownloadQueueItem.Status.QUEUED)) {
            submit(q.getId());
        }
    }
}
