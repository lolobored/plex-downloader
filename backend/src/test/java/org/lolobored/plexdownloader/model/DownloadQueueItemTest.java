package org.lolobored.plexdownloader.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DownloadQueueItemTest {
    @Test
    void defaultStatusIsQueued() {
        assertThat(new DownloadQueueItem().getStatus())
            .isEqualTo(DownloadQueueItem.Status.QUEUED);
    }

    @Test
    void progressDefaultsToNull() {
        assertThat(new DownloadQueueItem().getProgressPercent()).isNull();
    }
}
