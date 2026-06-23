// SubtitleScanStatus.java
package org.lolobored.plexdownloader.dto;
import java.time.Instant;
public record SubtitleScanStatus(boolean running, Instant lastRunAt, int scanned, int failed, int remainingUnknown) {}
