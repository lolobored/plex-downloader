package org.lolobored.plexdownloader.service;

import org.lolobored.plexdownloader.client.TdarrClient;
import org.lolobored.plexdownloader.model.DownloadQueueItem;
import org.lolobored.plexdownloader.repository.DownloadQueueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Component
@RequiredArgsConstructor
public class TdarrSyncScheduler implements SchedulingConfigurer {

    private static final String DEFAULT_CRON = "0 */30 * * * *";

    private final TdarrClient tdarrClient;
    private final DownloadQueueRepository queueRepo;
    private final SettingsService settings;

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addTriggerTask(
            this::syncAll,
            ctx -> {
                String cron = settings.get("tdarr.sync.cron").filter(s -> !s.isBlank()).orElse(DEFAULT_CRON);
                return new CronTrigger(cron).nextExecution(ctx);
            }
        );
    }

    void syncAll() {
        List<DownloadQueueItem> items = queueRepo.findByStatusAndTdarrStatusNotIn(
            DownloadQueueItem.Status.DONE,
            List.of(DownloadQueueItem.TdarrStatus.TRANSCODED, DownloadQueueItem.TdarrStatus.TDARR_ERROR)
        );
        log.info("Tdarr sync: checking {} items", items.size());
        for (DownloadQueueItem item : items) {
            try {
                applyTdarrStatus(item);
            } catch (Exception e) {
                log.error("Tdarr sync failed for item {}: {}", item.getId(), e.getMessage());
            }
        }
        pruneConversionDirs();
    }

    /** Refresh Tdarr status for a single queue item by id. Returns the updated item. */
    public DownloadQueueItem syncOne(Long id) {
        DownloadQueueItem item = queueRepo.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Queue item not found: " + id));
        if (item.getStatus() != DownloadQueueItem.Status.DONE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Can only refresh Tdarr status for DONE items");
        }
        applyTdarrStatus(item);
        pruneConversionDirs();
        return item;
    }

    /** Requeue a TDARR_ERROR item back to Tdarr. Resets status to DONE/NONE. */
    public DownloadQueueItem requeueOne(Long id) {
        DownloadQueueItem item = queueRepo.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Queue item not found: " + id));
        if (item.getStatus() != DownloadQueueItem.Status.ERROR
                || item.getTdarrStatus() != DownloadQueueItem.TdarrStatus.TDARR_ERROR) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Item is not in TDARR_ERROR state (status=" + item.getStatus()
                + " tdarrStatus=" + item.getTdarrStatus() + ")");
        }
        try {
            tdarrClient.requeueFile(item.getDestFilePath());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Tdarr requeue failed: " + e.getMessage());
        }
        item.setStatus(DownloadQueueItem.Status.DONE);
        item.setTdarrStatus(DownloadQueueItem.TdarrStatus.NONE);
        item.setErrorMessage(null);
        item.setTdarrError(null);
        queueRepo.save(item);
        log.info("Tdarr requeue: item={} reset to DONE/NONE", id);
        return item;
    }

    void applyTdarrStatus(DownloadQueueItem item) {
        Optional<TdarrClient.TdarrFileStatus> statusOpt = tdarrClient.getFileStatus(item.getDestFilePath());
        if (statusOpt.isEmpty()) {
            log.warn("Tdarr unreachable, skipping item {}", item.getId());
            return;
        }
        TdarrClient.TdarrFileStatus ts = statusOpt.get();
        DownloadQueueItem.TdarrStatus newStatus = ts.status();
        String outputPath = null;

        if (newStatus == DownloadQueueItem.TdarrStatus.NONE) {
            // In-flight entry gone from Tdarr — file may have been transcoded and re-tracked in libraries
            Path libFile = deriveLibrariesPath(item.getDestFilePath());
            if (libFile != null) {
                Optional<TdarrClient.TdarrFileStatus> libStatusOpt = tdarrClient.getFileStatus(libFile.toString());
                if (libStatusOpt.isPresent()
                        && libStatusOpt.get().status() == DownloadQueueItem.TdarrStatus.TRANSCODED) {
                    newStatus = DownloadQueueItem.TdarrStatus.TRANSCODED;
                    outputPath = libFile.toString();
                    log.info("Item {} transcoded, found output at {}", item.getId(), libFile);
                }
            }
            // Don't downgrade already-TRANSCODED items if Tdarr entry simply isn't found
            if (newStatus == DownloadQueueItem.TdarrStatus.NONE
                    && item.getTdarrStatus() == DownloadQueueItem.TdarrStatus.TRANSCODED) {
                log.debug("Item {} already TRANSCODED, in-flight entry gone — keeping status", item.getId());
                return;
            }
        }

        item.setTdarrStatus(newStatus);
        item.setTdarrError(ts.errorMessage());
        if (outputPath != null) {
            item.setOutputFilePath(outputPath);
        }
        if (newStatus == DownloadQueueItem.TdarrStatus.TDARR_ERROR) {
            item.setStatus(DownloadQueueItem.Status.ERROR);
            String detail = ts.errorMessage() != null ? ts.errorMessage() : "unknown error";
            item.setErrorMessage("Tdarr transcoding failed: " + detail);
        }
        queueRepo.save(item);
        log.info("Tdarr status updated: item={} tdarrStatus={} status={}",
            item.getId(), newStatus, item.getStatus());
    }

    private void pruneConversionDirs() {
        String conversionDir = settings.get("plex.conversion.dir").orElse("/plex-conversion");
        pruneEmptyDirs(Path.of(conversionDir, "in-flight"));
        pruneEmptyDirs(Path.of(conversionDir, "libraries"));
    }

    private void pruneEmptyDirs(Path root) {
        if (!Files.isDirectory(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                  .filter(p -> !p.equals(root))
                  .filter(Files::isDirectory)
                  .forEach(p -> {
                      try {
                          if (!Files.list(p).findFirst().isPresent()) {
                              Files.delete(p);
                              log.debug("Deleted empty dir: {}", p);
                          }
                      } catch (IOException e) {
                          log.warn("Could not prune dir {}: {}", p, e.getMessage());
                      }
                  });
        } catch (IOException e) {
            log.warn("Could not walk {} for pruning: {}", root, e.getMessage());
        }
    }

    /**
     * Derives the libraries-equivalent path for an in-flight file.
     * Walks the parent dir to handle extension changes (e.g. .m4v → .mp4).
     */
    Path deriveLibrariesPath(String destFilePath) {
        String candidate = destFilePath.replace("/in-flight/", "/libraries/");
        if (candidate.equals(destFilePath)) return null; // path had no /in-flight/ segment
        Path exact = Path.of(candidate);
        if (Files.exists(exact)) return exact;
        Path parent = exact.getParent();
        String stem = exact.getFileName().toString().replaceFirst("\\.[^.]+$", "");
        if (!Files.isDirectory(parent)) return null;
        try (var stream = Files.list(parent)) {
            return stream
                .filter(p -> p.getFileName().toString().startsWith(stem))
                .findFirst()
                .orElse(null);
        } catch (IOException e) {
            log.warn("Could not search libraries dir {}: {}", parent, e.getMessage());
            return null;
        }
    }
}
