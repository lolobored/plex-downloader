package org.lolobored.plexdownloader.transcode;

import org.lolobored.plexdownloader.model.DownloadQueueItem;
import org.lolobored.plexdownloader.repository.DownloadQueueRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final TranscodeConfig transcodeConfig;
    private final ApplicationEventPublisher eventPublisher;

    private final ConcurrentHashMap<Long, RunningTranscode> running = new ConcurrentHashMap<>();

    /** Tracks in-flight prefetch futures keyed by itemId. At most one entry at a time. */
    private final ConcurrentHashMap<Long, Future<?>> prefetches = new ConcurrentHashMap<>();

    private final ExecutorService prefetchPool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "prefetch-worker");
        t.setDaemon(true);
        return t;
    });

    public TranscodeService(DownloadQueueRepository queueRepo,
                            MediaProbe mediaProbe,
                            FfmpegCommandBuilder commandBuilder,
                            ProgressParser progressParser,
                            ProcessRunner processRunner,
                            TranscodeConfig transcodeConfig,
                            ApplicationEventPublisher eventPublisher) {
        this.queueRepo = queueRepo;
        this.mediaProbe = mediaProbe;
        this.commandBuilder = commandBuilder;
        this.progressParser = progressParser;
        this.processRunner = processRunner;
        this.transcodeConfig = transcodeConfig;
        this.eventPublisher = eventPublisher;
    }

    // ── Temp-path helpers ─────────────────────────────────────────────────────

    Path tempItemDir(Long itemId) {
        return Path.of(transcodeConfig.tempDir(), "plex-downloader", itemId.toString());
    }

    Path tempSrcFile(Long itemId, String sourceFilePath) {
        String sourceFilename = Path.of(sourceFilePath).getFileName().toString();
        return tempItemDir(itemId).resolve("src").resolve(sourceFilename);
    }

    // ── Prefetch API ──────────────────────────────────────────────────────────

    /**
     * Begins copying {@code item.sourceFilePath} → the item's temp src path asynchronously.
     * One-ahead only: if a prefetch is already in flight, this is a no-op.
     * Does NOT change the item's DB status (item stays QUEUED).
     */
    public void prefetchSource(Long itemId) {
        if (!prefetches.isEmpty()) {
            log.debug("Prefetch already in flight, skipping prefetch for item={}", itemId);
            return;
        }
        DownloadQueueItem item = queueRepo.findByIdWithProfile(itemId).orElse(null);
        if (item == null || item.getStatus() != DownloadQueueItem.Status.QUEUED) {
            log.debug("Prefetch skipped for item={}: missing or not QUEUED", itemId);
            return;
        }

        String sourceFilePath = item.getSourceFilePath();
        Path tempSrc = tempSrcFile(itemId, sourceFilePath);
        Path tempSrcDir = tempSrc.getParent();

        Future<?> future = prefetchPool.submit(() -> {
            try {
                log.info("Prefetching source for item={} src={}", itemId, sourceFilePath);
                Files.createDirectories(tempSrcDir);
                Files.copy(Path.of(sourceFilePath), tempSrc, StandardCopyOption.REPLACE_EXISTING);
                log.info("Prefetch complete: item={} bytes={}", itemId, Files.size(tempSrc));
            } catch (Exception e) {
                log.warn("Prefetch failed for item={}: {}", itemId, e.getMessage());
                deleteTempDir(tempItemDir(itemId));
                prefetches.remove(itemId);
            }
        });
        prefetches.put(itemId, future);
    }

    /**
     * Cancels an in-flight prefetch and cleans up the item's temp dir.
     * No-op if no prefetch is registered for the item.
     */
    public void cancelPrefetch(Long itemId) {
        Future<?> future = prefetches.remove(itemId);
        if (future == null) return;
        future.cancel(true);
        deleteTempDir(tempItemDir(itemId));
        log.info("Prefetch cancelled for item={}", itemId);
    }

    /** Package-private for testing. */
    int getPrefetchCount() { return prefetches.size(); }

    // ── Main transcode ────────────────────────────────────────────────────────

    public void transcode(Long itemId) {
        DownloadQueueItem item = queueRepo.findByIdWithProfile(itemId).orElse(null);
        if (item == null) { log.warn("Transcode skipped, item {} gone", itemId); return; }

        // Compute temp paths: <tempDir>/plex-downloader/<itemId>/
        //   src/<sourceFilename>  — local copy of NAS source
        //   <outName>            — ffmpeg output (same as #61)
        String outName = Path.of(item.getDestFilePath()).getFileName().toString();
        Path tempItemDir = tempItemDir(itemId);
        Path tempSrcDir  = tempItemDir.resolve("src");
        Path tempSrcFile = tempSrcFile(itemId, item.getSourceFilePath());
        Path tempFile    = tempItemDir.resolve(outName);

        // ── FETCHING: copy source from NAS to local temp (or consume prefetch) ─
        item.setStatus(DownloadQueueItem.Status.FETCHING);
        item.setTranscodeStartedAt(Instant.now());
        item.setProgressPercent(0);
        item.setTranscodeError(null);
        queueRepo.save(item);

        try {
            Future<?> prefetchFuture = prefetches.remove(itemId);
            if (prefetchFuture != null) {
                // Await the prefetch — if it failed/was cancelled, fall through to a normal copy
                boolean prefetchOk = false;
                try {
                    prefetchFuture.get();
                    prefetchOk = true;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ee) {
                    log.warn("Prefetch for item={} failed ({}), will re-copy source", itemId, ee.getCause().getMessage());
                }
                if (prefetchOk && Files.exists(tempSrcFile) && Files.size(tempSrcFile) > 0) {
                    log.info("Prefetch hit: skipping source copy for item={} bytes={}", itemId, Files.size(tempSrcFile));
                    // Source already staged — skip the copy
                } else {
                    // Prefetch failed or produced an empty file — fall back to a normal copy
                    Files.createDirectories(tempSrcDir);
                    log.info("Prefetch miss: re-copying source for item={}", itemId);
                    Files.copy(Path.of(item.getSourceFilePath()), tempSrcFile, StandardCopyOption.REPLACE_EXISTING);
                    log.info("Source fetched (fallback): item={} bytes={}", itemId, Files.size(tempSrcFile));
                }
            } else {
                Files.createDirectories(tempSrcDir);
                log.info("Fetching source to local temp: item={} src={}", itemId, item.getSourceFilePath());
                Files.copy(Path.of(item.getSourceFilePath()), tempSrcFile, StandardCopyOption.REPLACE_EXISTING);
                log.info("Source fetched: item={} bytes={}", itemId, Files.size(tempSrcFile));
            }
        } catch (IOException e) {
            deleteTempDir(tempItemDir);
            failItem(item, "Source copy failed: " + e.getMessage());
            return;
        }

        // ── TRANSCODING: probe local copy, build cmd, run ffmpeg ─────────────
        // Any unexpected exception here (UncheckedIOException from processRunner.start,
        // RuntimeException from mediaProbe/commandBuilder, etc.) must clean up temp and
        // mark the item ERROR rather than leaving it stuck in TRANSCODING/FETCHING.
        try {
            item.setStatus(DownloadQueueItem.Status.TRANSCODING);
            queueRepo.save(item);

            MediaInfo info = mediaProbe.probe(tempSrcFile.toString());

            List<String> cmd = commandBuilder.build(item.getQualityProfile(),
                tempSrcFile.toString(), tempFile.toString(), info);

            Deque<String> stderrTail = new ArrayDeque<>();
            AtomicInteger lastPct = new AtomicInteger(-1);
            AtomicBoolean nearDoneFired = new AtomicBoolean(false);

            RunningTranscode rt = processRunner.start(cmd,
                line -> {
                    OptionalInt pct = progressParser.percentFor(line, info.durationSeconds());
                    if (pct.isPresent()) {
                        int p = pct.getAsInt();
                        if (lastPct.getAndSet(p) != p) {
                            persistProgress(itemId, p);
                        }
                        if (p >= 90 && nearDoneFired.compareAndSet(false, true)) {
                            eventPublisher.publishEvent(new TranscodeNearDoneEvent(itemId));
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
                    // Capture sizes from LOCAL temp files before the move (Fix 3)
                    FileSizeStats stats = fileSizeStats(tempSrcFile.toString(), tempFile.toString());
                    moveFile(tempFile, destPath);
                    fresh.setStatus(DownloadQueueItem.Status.DONE);
                    fresh.setProgressPercent(100);
                    fresh.setCompletedAt(Instant.now());
                    fresh.setSourceSizeBytes(stats.srcSize());
                    fresh.setOutputSizeBytes(stats.destSize());
                    fresh.setCompressionRatio(stats.ratio());
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
        } catch (Exception e) {
            // Unexpected exception (e.g. UncheckedIOException from processRunner.start,
            // RuntimeException from mediaProbe/commandBuilder) — clean up and fail the item.
            deleteTempDir(tempItemDir);
            DownloadQueueItem freshOnError = queueRepo.findById(itemId).orElse(item);
            failItem(freshOnError, "Transcode setup failed: " + e.getMessage());
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

    record FileSizeStats(Long srcSize, Long destSize, Double ratio) {}

    /** Stats source and dest once; computes space saved as a percentage of source size, rounded to 1 decimal. */
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

    private void deleteTempDir(Path tempItemDir) {
        if (!Files.exists(tempItemDir)) return;
        try (var stream = Files.walk(tempItemDir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException e) {
                    log.warn("Could not delete temp path {}: {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Could not walk temp dir {}: {}", tempItemDir, e.getMessage());
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
