package org.lolobored.plexdownloader.transcode;

import org.lolobored.plexdownloader.model.DownloadQueueItem;
import org.lolobored.plexdownloader.repository.DownloadQueueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private final ConcurrentHashMap<Long, RunningTranscode> running = new ConcurrentHashMap<>();

    public void transcode(Long itemId) {
        DownloadQueueItem item = queueRepo.findById(itemId).orElse(null);
        if (item == null) { log.warn("Transcode skipped, item {} gone", itemId); return; }

        item.setStatus(DownloadQueueItem.Status.TRANSCODING);
        item.setTranscodeStartedAt(Instant.now());
        item.setProgressPercent(0);
        item.setTranscodeError(null);
        queueRepo.save(item);

        MediaInfo info = mediaProbe.probe(item.getSourceFilePath());
        List<String> cmd = commandBuilder.build(item.getQualityProfile(),
            item.getSourceFilePath(), item.getDestFilePath(), info);

        try {
            Files.createDirectories(Path.of(item.getDestFilePath()).getParent());
        } catch (IOException e) {
            failItem(item, "Could not create output dir: " + e.getMessage());
            return;
        }

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
        if (fresh == null) return; // cancelled + deleted
        if (exit == 0) {
            fresh.setStatus(DownloadQueueItem.Status.DONE);
            fresh.setProgressPercent(100);
            fresh.setCompletedAt(Instant.now());
            queueRepo.save(fresh);
            log.info("Transcode done: item={}", itemId);
        } else {
            String tail;
            synchronized (stderrTail) { tail = String.join("\n", stderrTail); }
            deletePartial(fresh.getDestFilePath());
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

    private void deletePartial(String dest) {
        if (dest == null) return;
        try {
            Files.deleteIfExists(Path.of(dest));
        } catch (IOException e) {
            log.warn("Could not delete partial output {}: {}", dest, e.getMessage());
        }
    }
}
