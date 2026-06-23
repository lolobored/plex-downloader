package org.lolobored.plexdownloader.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.config.TriggerTask;
import org.springframework.scheduling.support.SimpleTriggerContext;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubtitleScanSchedulerTest {

    @Mock SubtitleScanService scanService;
    @Mock SettingsService settings;
    @InjectMocks SubtitleScanScheduler scheduler;

    private org.springframework.scheduling.Trigger registeredTrigger() {
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        scheduler.configureTasks(registrar);
        TriggerTask task = registrar.getTriggerTaskList().get(0);
        return task.getTrigger();
    }

    @Test
    void absent_setting_usesDefaultCron_returnsNonNull() {
        when(settings.get("subtitles.scan.cron")).thenReturn(Optional.empty());

        org.springframework.scheduling.Trigger trigger = registeredTrigger();
        Instant next = trigger.nextExecution(new SimpleTriggerContext());

        assertThat(next).isNotNull();
    }

    @Test
    void blank_setting_disablesSchedule_returnsNull() {
        when(settings.get("subtitles.scan.cron")).thenReturn(Optional.of(""));

        org.springframework.scheduling.Trigger trigger = registeredTrigger();
        Instant next = trigger.nextExecution(new SimpleTriggerContext());

        assertThat(next).isNull();
    }

    @Test
    void present_cron_value_returnsNonNull() {
        when(settings.get("subtitles.scan.cron")).thenReturn(Optional.of("0 0 3 * * *"));

        org.springframework.scheduling.Trigger trigger = registeredTrigger();
        Instant next = trigger.nextExecution(new SimpleTriggerContext());

        assertThat(next).isNotNull();
    }
}
