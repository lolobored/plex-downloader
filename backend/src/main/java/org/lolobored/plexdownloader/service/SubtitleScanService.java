// SubtitleScanService.java
package org.lolobored.plexdownloader.service;

import org.lolobored.plexdownloader.dto.SubtitleScanStatus;
import org.lolobored.plexdownloader.model.*;
import org.lolobored.plexdownloader.repository.*;
import org.lolobored.plexdownloader.transcode.SubtitleProbe;
import org.lolobored.plexdownloader.util.SubtitleLangs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubtitleScanService {

    private final MovieRepository movieRepo;
    private final EpisodeRepository episodeRepo;
    private final DownloadQueueRepository queueRepo;
    private final SubtitleProbe subtitleProbe;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Instant lastRunAt;
    private volatile int scanned, failed;

    public boolean isRunning() { return running.get(); }

    public SubtitleScanStatus status() {
        int remaining = movieRepo.findBySubtitlesScannedAtIsNullAndFilePathIsNotNull().size()
                      + episodeRepo.findBySubtitlesScannedAtIsNullAndFilePathIsNotNull().size()
                      + queueRepo.findByStatusAndOutputSubtitlesScannedAtIsNullAndDestFilePathIsNotNull(DownloadQueueItem.Status.DONE).size();
        return new SubtitleScanStatus(running.get(), lastRunAt, scanned, failed, remaining);
    }

    /** Scans source (movie/episode) + output (DONE queue) files. force=true re-scans all. */
    public void scan(boolean force) {
        if (!running.compareAndSet(false, true)) {
            log.info("Subtitle scan already running, skipping");
            return;
        }
        scanned = 0; failed = 0;
        try {
            List<Movie> movies = force ? movieRepo.findAll() : movieRepo.findBySubtitlesScannedAtIsNull();
            for (Movie m : movies) {
                if (m.getFilePath() == null) continue;
                SubtitleProbe.ProbeResult r = subtitleProbe.probe(m.getFilePath());
                if (r.ok()) { m.setSubtitleLangs(SubtitleLangs.toCsv(r.langs())); m.setSubtitlesScannedAt(Instant.now()); movieRepo.save(m); scanned++; }
                else failed++;
                throttle();
            }
            List<Episode> eps = force ? episodeRepo.findAll() : episodeRepo.findBySubtitlesScannedAtIsNull();
            for (Episode e : eps) {
                if (e.getFilePath() == null) continue;
                SubtitleProbe.ProbeResult r = subtitleProbe.probe(e.getFilePath());
                if (r.ok()) { e.setSubtitleLangs(SubtitleLangs.toCsv(r.langs())); e.setSubtitlesScannedAt(Instant.now()); episodeRepo.save(e); scanned++; }
                else failed++;
                throttle();
            }
            List<DownloadQueueItem> outs = force
                ? queueRepo.findByStatus(DownloadQueueItem.Status.DONE)
                : queueRepo.findByStatusAndOutputSubtitlesScannedAtIsNull(DownloadQueueItem.Status.DONE);
            for (DownloadQueueItem q : outs) {
                if (q.getDestFilePath() == null) continue;
                SubtitleProbe.ProbeResult r = subtitleProbe.probe(q.getDestFilePath());
                if (r.ok()) { q.setOutputSubtitleLangs(SubtitleLangs.toCsv(r.langs())); q.setOutputSubtitlesScannedAt(Instant.now()); queueRepo.save(q); scanned++; }
                else failed++;
                throttle();
            }
            lastRunAt = Instant.now();
            log.info("Subtitle scan done: scanned={} failed={}", scanned, failed);
        } finally {
            running.set(false);
        }
    }

    private void throttle() {
        try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
