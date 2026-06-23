package org.lolobored.plexdownloader.controller;

import org.lolobored.plexdownloader.dto.SubtitleScanStatus;
import org.lolobored.plexdownloader.service.SubtitleScanScheduler;
import org.lolobored.plexdownloader.service.SubtitleScanService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SubtitleScanControllerTest {

    @Mock SubtitleScanService scanService;
    @Mock SubtitleScanScheduler scheduler;
    @InjectMocks SubtitleScanController controller;

    @Test
    void scan_notRunning_returns202AndTriggersManual() {
        when(scanService.isRunning()).thenReturn(false);

        ResponseEntity<Void> response = controller.scan(false);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(scheduler).triggerManual(false);
    }

    @Test
    void scan_withForceTrue_triggersManualWithForce() {
        when(scanService.isRunning()).thenReturn(false);

        ResponseEntity<Void> response = controller.scan(true);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(scheduler).triggerManual(true);
    }

    @Test
    void scan_alreadyRunning_returns409AndDoesNotTrigger() {
        when(scanService.isRunning()).thenReturn(true);

        ResponseEntity<Void> response = controller.scan(false);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verify(scheduler, never()).triggerManual(anyBoolean());
    }

    @Test
    void status_returnsSubtitleScanStatus() {
        SubtitleScanStatus expected = new SubtitleScanStatus(false, Instant.now(), 10, 2, 5);
        when(scanService.status()).thenReturn(expected);

        SubtitleScanStatus result = controller.status();

        assertThat(result).isEqualTo(expected);
    }
}
