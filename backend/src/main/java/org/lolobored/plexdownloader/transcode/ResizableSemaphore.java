package org.lolobored.plexdownloader.transcode;

import java.util.concurrent.Semaphore;

/**
 * A Semaphore whose maximum permit count can be adjusted at runtime.
 * <p>
 * Increasing the max releases extra permits immediately, allowing waiting
 * threads to proceed.  Decreasing the max reduces the available permits
 * (possibly going negative if permits are already held), so in-flight
 * work is unaffected but new acquisitions are throttled until the pool
 * drains to the new bound.
 */
public class ResizableSemaphore extends Semaphore {

    private int maxPermits;

    public ResizableSemaphore(int initialPermits) {
        super(initialPermits);
        this.maxPermits = initialPermits;
    }

    public synchronized void setMaxPermits(int newMax) {
        newMax = Math.max(1, newMax);
        int delta = newMax - this.maxPermits;
        this.maxPermits = newMax;
        if (delta > 0) {
            release(delta);
        } else if (delta < 0) {
            reducePermits(-delta);   // reducePermits is protected on Semaphore — accessible here
        }
        // delta == 0: no-op
    }

    public synchronized int getMaxPermits() {
        return maxPermits;
    }
}
