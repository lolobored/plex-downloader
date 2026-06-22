package org.lolobored.plexdownloader.transcode;

import org.springframework.stereotype.Component;
import java.util.OptionalInt;

@Component
public class ProgressParser {

    public OptionalInt percentFor(String line, double durationSeconds) {
        if (line == null) return OptionalInt.empty();
        String s = line.trim();
        if (s.equals("progress=end")) return OptionalInt.of(100);

        Double seconds = null;
        if (s.startsWith("out_time_us=")) {
            try {
                seconds = Long.parseLong(s.substring("out_time_us=".length()).trim()) / 1_000_000.0;
            } catch (NumberFormatException ignored) { return OptionalInt.empty(); }
        } else if (s.startsWith("out_time=")) {
            seconds = parseTimestamp(s.substring("out_time=".length()).trim());
        }
        if (seconds == null) return OptionalInt.empty();
        if (durationSeconds <= 0) return OptionalInt.empty();

        long pct = Math.round(seconds / durationSeconds * 100.0);
        if (pct < 0) pct = 0;
        if (pct > 100) pct = 100;
        return OptionalInt.of((int) pct);
    }

    private Double parseTimestamp(String ts) {
        try {
            String[] parts = ts.split(":");
            if (parts.length != 3) return null;
            int h = Integer.parseInt(parts[0]);
            int m = Integer.parseInt(parts[1]);
            double sec = Double.parseDouble(parts[2]);
            return h * 3600.0 + m * 60.0 + sec;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
