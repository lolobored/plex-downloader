package org.lolobored.plexdownloader.service;

import org.lolobored.plexdownloader.repository.ShowSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WatchedSyncScheduler implements SchedulingConfigurer {

    private static final String DEFAULT_CRON = "0 */15 * * * *";

    private final WatchedSyncService watchedSyncService;
    private final SubscriptionService subscriptionService;
    private final ShowSubscriptionRepository showSubscriptionRepository;
    private final SettingsService settings;

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addTriggerTask(
            this::syncAll,
            ctx -> {
                String cron = settings.get("watched.sync.cron").filter(s -> !s.isBlank()).orElse(DEFAULT_CRON);
                return new CronTrigger(cron).nextExecution(ctx);
            }
        );
    }

    void syncAll() {
        log.info("Watched sync starting for all subscriptions");
        showSubscriptionRepository.findAllWithUserAndShow().forEach(sub -> {
            try {
                watchedSyncService.syncShow(sub.getUser().getId(), sub.getShow().getId());
                subscriptionService.replenish(sub);
            } catch (Exception e) {
                log.error("Watched sync failed for user={} show={}: {}",
                    sub.getUser().getId(), sub.getShow().getId(), e.getMessage());
            }
        });
    }
}
