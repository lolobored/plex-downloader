package org.lolobored.plexdownloader.transcode;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ResizableSemaphoreTest {

    @Test
    void initialPermitsAvailable() {
        ResizableSemaphore s = new ResizableSemaphore(2);
        assertThat(s.getMaxPermits()).isEqualTo(2);
        assertThat(s.availablePermits()).isEqualTo(2);
    }

    @Test
    void increaseReleasesExtraPermits() {
        ResizableSemaphore s = new ResizableSemaphore(2);
        s.setMaxPermits(4);
        assertThat(s.getMaxPermits()).isEqualTo(4);
        assertThat(s.availablePermits()).isEqualTo(4);
    }

    @Test
    void decreaseWhileIdleReducesAvailable() {
        ResizableSemaphore s = new ResizableSemaphore(4);
        s.setMaxPermits(2);
        assertThat(s.getMaxPermits()).isEqualTo(2);
        assertThat(s.availablePermits()).isEqualTo(2);
    }

    @Test
    void decreaseWhileAllHeldGoesNegativeOrZero_thenReleaseAllRestoresToNewMax() throws Exception {
        ResizableSemaphore s = new ResizableSemaphore(2);
        s.setMaxPermits(4);
        // Acquire all 4
        s.acquire(4);
        assertThat(s.availablePermits()).isEqualTo(0);

        // Shrink to 2 while 4 are held → availablePermits goes to -2 (reducePermits reduces by 2)
        s.setMaxPermits(2);
        assertThat(s.availablePermits()).isLessThanOrEqualTo(0);

        // Release all 4 → availablePermits should be 2 (the new max), not 4
        s.release(4);
        assertThat(s.availablePermits()).isEqualTo(2);
        assertThat(s.getMaxPermits()).isEqualTo(2);
    }

    @Test
    void setMaxPermitsZeroClampsToOne() {
        ResizableSemaphore s = new ResizableSemaphore(2);
        s.setMaxPermits(0);
        assertThat(s.getMaxPermits()).isEqualTo(1);
        assertThat(s.availablePermits()).isEqualTo(1);
    }

    @Test
    void setMaxPermitsNegativeClampsToOne() {
        ResizableSemaphore s = new ResizableSemaphore(2);
        s.setMaxPermits(-5);
        assertThat(s.getMaxPermits()).isEqualTo(1);
        assertThat(s.availablePermits()).isEqualTo(1);
    }

    @Test
    void increaseFromOneToThreeAvailableIsThree() {
        ResizableSemaphore s = new ResizableSemaphore(1);
        s.setMaxPermits(3);
        assertThat(s.availablePermits()).isEqualTo(3);
        assertThat(s.getMaxPermits()).isEqualTo(3);
    }
}
