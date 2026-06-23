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
public class SubtitleScanScheduler implements SchedulingConfigurer {

    private static final String DEFAULT_CRON = "0 0 4 * * *";

    private final SubtitleScanService scanService;
    private final SettingsService settings;

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addTriggerTask(
            () -> scanService.scan(false),
            ctx -> {
                String cron = settings.get("subtitles.scan.cron").orElse(DEFAULT_CRON);
                if (cron.isBlank()) return null; // present-blank -> disabled
                return new CronTrigger(cron).nextExecution(ctx);
            }
        );
    }

    @Async
    public void triggerManual(boolean force) {
        log.info("Manual subtitle scan triggered (force={})", force);
        scanService.scan(force);
    }
}
