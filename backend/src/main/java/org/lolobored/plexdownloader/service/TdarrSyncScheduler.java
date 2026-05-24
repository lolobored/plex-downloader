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

@Slf4j
@Component
@RequiredArgsConstructor
public class TdarrSyncScheduler implements SchedulingConfigurer {

    private static final String DEFAULT_CRON = "0 */30 * * * *";

    private final TdarrClient tdarrClient;
    private final DownloadQueueRepository queueRepo;
    private final SettingsService settings;
    private final PathMappingService pathMapping;

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
                String tdarrPath = pathMapping.appToTdarr(item.getDestFilePath());
                Optional<TdarrClient.TdarrFileStatus> statusOpt = tdarrClient.getFileStatus(tdarrPath);
                if (statusOpt.isEmpty()) {
                    log.warn("Tdarr unreachable, skipping item {}", item.getId());
                    continue;
                }
                TdarrClient.TdarrFileStatus ts = statusOpt.get();
                item.setTdarrStatus(ts.status());
                item.setTdarrError(ts.errorMessage());
                if (ts.status() == DownloadQueueItem.TdarrStatus.TRANSCODED
                        && ts.outputFilePath() != null) {
                    item.setOutputFilePath(pathMapping.tdarrToApp(ts.outputFilePath()));
                }
                queueRepo.save(item);
                log.info("Tdarr status updated: item={} status={}", item.getId(), ts.status());
            } catch (Exception e) {
                log.error("Tdarr sync failed for item {}: {}", item.getId(), e.getMessage());
            }
        }
    }
}
