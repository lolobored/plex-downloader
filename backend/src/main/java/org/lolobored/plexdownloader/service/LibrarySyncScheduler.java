package org.lolobored.plexdownloader.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class LibrarySyncScheduler implements SchedulingConfigurer {

    private static final String DEFAULT_CRON = "0 0 */6 * * *";

    private final LibrarySyncService syncService;
    private final SettingsService settings;

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addTriggerTask(
            this::runSync,
            ctx -> {
                String cron = settings.get("plex.sync.cron").orElse(DEFAULT_CRON);
                return new CronTrigger(cron).nextExecution(ctx);
            }
        );
    }

    @Async
    public void triggerManual() {
        log.info("Manual sync triggered");
        syncService.syncAll();
    }

    private void runSync() {
        log.info("Scheduled sync starting");
        syncService.syncAll();
    }
}
