package org.lolobored.plexdownloader.transcode;

import org.lolobored.plexdownloader.model.DownloadQueueItem;
import org.lolobored.plexdownloader.repository.DownloadQueueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class TranscodeService {

    private static final int STDERR_TAIL = 20;

    private final DownloadQueueRepository queueRepo;
    private final MediaProbe mediaProbe;
    private final FfmpegCommandBuilder commandBuilder;
    private final ProgressParser progressParser;
    private final ProcessRunner processRunner;
    private final TranscodeConfig transcodeConfig;

    private final ConcurrentHashMap<Long, RunningTranscode> running = new ConcurrentHashMap<>();

    public void transcode(Long itemId) {
        DownloadQueueItem item = queueRepo.findByIdWithProfile(itemId).orElse(null);
        if (item == null) { log.warn("Transcode skipped, item {} gone", itemId); return; }

        item.setStatus(DownloadQueueItem.Status.TRANSCODING);
        item.setTranscodeStartedAt(Instant.now());
        item.setProgressPercent(0);
        item.setTranscodeError(null);
        queueRepo.save(item);

        MediaInfo info = mediaProbe.probe(item.getSourceFilePath());

        // Compute temp output path: <tempDir>/plex-downloader/<itemId>/<outName>
        String outName = Path.of(item.getDestFilePath()).getFileName().toString();
        Path tempItemDir = Path.of(transcodeConfig.tempDir(), "plex-downloader", itemId.toString());
        Path tempFile = tempItemDir.resolve(outName);

        try {
            Files.createDirectories(tempItemDir);
        } catch (IOException e) {
            failItem(item, "Could not create temp dir: " + e.getMessage());
            return;
        }

        List<String> cmd = commandBuilder.build(item.getQualityProfile(),
            item.getSourceFilePath(), tempFile.toString(), info);

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
            // cancelled + deleted — clean up temp
            deleteTempDir(tempItemDir);
            return;
        }

        if (exit == 0) {
            // Move temp file → final destination
            Path destPath = Path.of(fresh.getDestFilePath());
            fresh.setStatus(DownloadQueueItem.Status.COPYING);
            queueRepo.save(fresh);

            try {
                Files.createDirectories(destPath.getParent());
                moveFile(tempFile, destPath);
                fresh.setStatus(DownloadQueueItem.Status.DONE);
                fresh.setProgressPercent(100);
                fresh.setCompletedAt(Instant.now());
                fresh.setCompressionRatio(compressionRatio(fresh.getSourceFilePath(), destPath.toString()));
                queueRepo.save(fresh);
                log.info("Transcode done: item={} compression={}%", itemId, fresh.getCompressionRatio());
            } catch (IOException e) {
                deletePartial(destPath.toString());
                failItem(fresh, "Move to destination failed: " + e.getMessage());
            } finally {
                deleteTempDir(tempItemDir);
            }
        } else {
            String tail;
            synchronized (stderrTail) { tail = String.join("\n", stderrTail); }
            deleteTempDir(tempItemDir);
            failItem(fresh, "ffmpeg exit " + exit + (tail.isBlank() ? "" : ": " + tail));
        }
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

    /** Space saved as a percentage of source size, rounded to 1 decimal. Null if either size is unavailable. */
    private Double compressionRatio(String source, String dest) {
        try {
            long srcSize = Files.size(Path.of(source));
            long destSize = Files.size(Path.of(dest));
            if (srcSize <= 0) return null;
            double saved = (srcSize - destSize) * 100.0 / srcSize;
            return Math.round(saved * 10.0) / 10.0;
        } catch (IOException | RuntimeException e) {
            log.warn("Could not compute compression ratio src={} dest={}: {}", source, dest, e.getMessage());
            return null;
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

    private void deleteTempDir(Path tempItemDir) {
        try {
            // Delete all files in the temp dir, then the dir itself
            if (Files.exists(tempItemDir)) {
                try (var stream = Files.list(tempItemDir)) {
                    for (Path f : stream.toList()) {
                        Files.deleteIfExists(f);
                    }
                }
                Files.deleteIfExists(tempItemDir);
            }
        } catch (IOException e) {
            log.warn("Could not clean up temp dir {}: {}", tempItemDir, e.getMessage());
        }
    }

    /**
     * Moves src to dest. Tries ATOMIC_MOVE first (same filesystem); falls back to
     * REPLACE_EXISTING (copy + delete) for cross-filesystem moves (e.g. local → NAS).
     */
    private void moveFile(Path src, Path dest) throws IOException {
        try {
            Files.move(src, dest, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            log.debug("Atomic move not supported (cross-filesystem?), falling back to copy+delete: {}", e.getMessage());
            Files.move(src, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
