package org.lolobored.plexdownloader.transcode;

public interface RunningTranscode {
    int waitForExit() throws InterruptedException;
    void cancel();
}
