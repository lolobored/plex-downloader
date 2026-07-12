package org.lolobored.plexdownloader.transcode;

import org.lolobored.plexdownloader.model.DownloadQueueItem;
import org.lolobored.plexdownloader.repository.DownloadQueueRepository;
import org.lolobored.plexdownloader.repository.EpisodeRepository;
import org.lolobored.plexdownloader.repository.MovieRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class TranscodeService {

    private static final int STDERR_TAIL = 20;

    private final DownloadQueueRepository queueRepo;
    private final MediaProbe mediaProbe;
    private final FfmpegCommandBuilder commandBuilder;
    private final ProgressParser progressParser;
    private final ProcessRunner processRunner;
    private final SubtitleProbe subtitleProbe;
    private final MovieRepository movieRepo;
    private final EpisodeRepository episodeRepo;

    private final ConcurrentHashMap<Long, RunningTranscode> running = new ConcurrentHashMap<>();

    public TranscodeService(DownloadQueueRepository queueRepo,
                            MediaProbe mediaProbe,
                            FfmpegCommandBuilder commandBuilder,
                            ProgressParser progressParser,
                            ProcessRunner processRunner,
                            SubtitleProbe subtitleProbe,
                            MovieRepository movieRepo,
                            EpisodeRepository episodeRepo) {
        this.queueRepo = queueRepo;
        this.mediaProbe = mediaProbe;
        this.commandBuilder = commandBuilder;
        this.progressParser = progressParser;
        this.processRunner = processRunner;
        this.subtitleProbe = subtitleProbe;
        this.movieRepo = movieRepo;
        this.episodeRepo = episodeRepo;
    }

    public void transcode(Long itemId) {
        DownloadQueueItem item = queueRepo.findByIdWithProfile(itemId).orElse(null);
        if (item == null) { log.warn("Transcode skipped, item {} gone", itemId); return; }

        // The queue item's source path is a snapshot taken at enqueue time. If the file was
        // renamed on disk afterwards (e.g. a re-download at a different quality), library sync
        // updates the canonical Movie/Episode.filePath but NOT this snapshot — leaving ffmpeg to
        // open a stale path ("No such file or directory"). Re-resolve from the canonical record
        // so the transcode self-heals, including on retry.
        if (!refreshSourcePath(item)) return;   // media gone: item already failed with a clear message

        item.setStatus(DownloadQueueItem.Status.TRANSCODING);
        item.setTranscodeStartedAt(Instant.now());
        item.setProgressPercent(0);
        item.setTranscodeError(null);
        queueRepo.save(item);

        try {
            MediaInfo info = mediaProbe.probe(item.getSourceFilePath());

            List<String> cmd = commandBuilder.build(item.getQualityProfile(),
                item.getSourceFilePath(), item.getDestFilePath(), info);

            Files.createDirectories(Path.of(item.getDestFilePath()).getParent());

            Deque<String> stderrTail = new ArrayDeque<>();
            AtomicInteger lastPct = new AtomicInteger(-1);

            RunningTranscode rt = processRunner.start(cmd,
                line -> {
                    OptionalInt pct = progressParser.percentFor(line, info.durationSeconds());
                    if (pct.isPresent()) {
                        int p = pct.getAsInt();
                        if (lastPct.getAndSet(p) != p) {
                            persistProgress(itemId, p);
                        }
                    }
                },
                line -> {
                    synchronized (stderrTail) {
                        stderrTail.addLast(line);
                        while (stderrTail.size() > STDERR_TAIL) stderrTail.removeFirst();
                    }
                });

            running.put(itemId, rt);
            int exit;
            try {
                exit = rt.waitForExit();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exit = -1;
            } finally {
                running.remove(itemId);
            }

            DownloadQueueItem fresh = queueRepo.findById(itemId).orElse(null);
            if (fresh == null) {
                deletePartial(item.getDestFilePath());
                return;
            }

            if (exit == 0) {
                FileSizeStats stats = fileSizeStats(fresh.getSourceFilePath(), fresh.getDestFilePath());
                fresh.setStatus(DownloadQueueItem.Status.DONE);
                fresh.setProgressPercent(100);
                fresh.setCompletedAt(Instant.now());
                fresh.setSourceSizeBytes(stats.srcSize());
                fresh.setOutputSizeBytes(stats.destSize());
                fresh.setCompressionRatio(stats.ratio());
                SubtitleProbe.ProbeResult subs = subtitleProbe.probe(fresh.getDestFilePath());
                if (subs.ok()) {
                    fresh.setOutputSubtitleLangs(org.lolobored.plexdownloader.util.SubtitleLangs.toCsv(subs.langs()));
                    fresh.setOutputSubtitlesScannedAt(Instant.now());
                }
                queueRepo.save(fresh);
                log.info("Transcode done: item={} compression={}%", itemId, fresh.getCompressionRatio());
            } else {
                String tail;
                synchronized (stderrTail) { tail = String.join("\n", stderrTail); }
                deletePartial(fresh.getDestFilePath());
                failItem(fresh, "ffmpeg exit " + exit + (tail.isBlank() ? "" : ": " + tail));
            }
        } catch (Exception e) {
            deletePartial(item.getDestFilePath());
            DownloadQueueItem freshOnError = queueRepo.findById(itemId).orElse(item);
            failItem(freshOnError, "Transcode setup failed: " + e.getMessage());
        }
    }

    /**
     * Re-resolves the queue item's source path from the canonical Movie/Episode record.
     * Returns false (after failing the item) when the media record no longer exists —
     * e.g. Plex reissued the ratingKey on an upgrade and sync pruned the old row, orphaning
     * this item's mediaId. Otherwise updates the snapshot if the path drifted and returns true.
     */
    private boolean refreshSourcePath(DownloadQueueItem item) {
        if (item.getMediaType() == null || item.getMediaId() == null) return true;

        boolean present = false;
        String current = null;
        switch (item.getMediaType()) {
            case MOVIE -> {
                var m = movieRepo.findById(item.getMediaId());
                present = m.isPresent();
                current = m.map(org.lolobored.plexdownloader.model.Movie::getFilePath).orElse(null);
            }
            case EPISODE -> {
                var e = episodeRepo.findById(item.getMediaId());
                present = e.isPresent();
                current = e.map(org.lolobored.plexdownloader.model.Episode::getFilePath).orElse(null);
            }
        }

        if (!present) {
            failItem(item, "Source media no longer in Plex - likely replaced by an upgrade; re-enqueue from library");
            return false;
        }

        if (current != null && !current.equals(item.getSourceFilePath())) {
            log.info("Source path drifted for item {}: {} -> {}",
                item.getId(), item.getSourceFilePath(), current);
            item.setSourceFilePath(current);
            queueRepo.save(item);
        }
        return true;
    }

    public boolean cancel(Long itemId) {
        RunningTranscode rt = running.get(itemId);
        if (rt == null) return false;
        rt.cancel();
        return true;
    }

    private void persistProgress(Long itemId, int pct) {
        queueRepo.findById(itemId).ifPresent(i -> {
            i.setProgressPercent(pct);
            queueRepo.save(i);
        });
    }

    private void failItem(DownloadQueueItem item, String message) {
        item.setStatus(DownloadQueueItem.Status.ERROR);
        item.setTranscodeError(message);
        item.setErrorMessage("Transcoding failed");
        queueRepo.save(item);
        log.error("Transcode failed: item={} {}", item.getId(), message);
    }

    record FileSizeStats(Long srcSize, Long destSize, Double ratio) {}

    private FileSizeStats fileSizeStats(String source, String dest) {
        try {
            long srcSize = Files.size(Path.of(source));
            long destSize = Files.size(Path.of(dest));
            if (srcSize <= 0) return new FileSizeStats(srcSize, destSize, null);
            double saved = (srcSize - destSize) * 100.0 / srcSize;
            double ratio = Math.round(saved * 10.0) / 10.0;
            return new FileSizeStats(srcSize, destSize, ratio);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not compute file size stats src={} dest={}: {}", source, dest, e.getMessage());
            return new FileSizeStats(null, null, null);
        }
    }

    private void deletePartial(String dest) {
        if (dest == null) return;
        try {
            Files.deleteIfExists(Path.of(dest));
        } catch (IOException e) {
            log.warn("Could not delete partial output {}: {}", dest, e.getMessage());
        }
    }
}
