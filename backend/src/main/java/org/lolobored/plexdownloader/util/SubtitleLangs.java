package org.lolobored.plexdownloader.util;

import java.util.ArrayList;
import java.util.List;

/** Subtitle languages stored comma-padded: "," = none, ",eng,fra," = two streams. */
public final class SubtitleLangs {
    private SubtitleLangs() {}

    public static String toCsv(List<String> langs) {
        if (langs == null || langs.isEmpty()) return ",";
        StringBuilder sb = new StringBuilder(",");
        for (String l : langs) {
            String v = (l == null || l.isBlank()) ? "und" : l.trim().toLowerCase();
            sb.append(v).append(',');
        }
        return sb.toString();
    }

    public static List<String> fromCsv(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null) return out;
        for (String p : csv.split(",")) {
            if (!p.isBlank()) out.add(p.trim().toLowerCase());
        }
        return out;
    }

    /** Comma-padded token for a `LIKE '%' || token || '%'` containment check. */
    public static String token(String lang) {
        return "," + (lang == null ? "" : lang.trim().toLowerCase()) + ",";
    }
}
