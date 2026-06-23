package org.lolobored.plexdownloader.transcode;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Probes a media file for its subtitle stream languages via ffprobe. */
@Component
@RequiredArgsConstructor
public class SubtitleProbe {

    private final ProcessRunner processRunner;
    private final TranscodeConfig config;

    public record ProbeResult(boolean ok, List<String> langs) {}

    public ProbeResult probe(String filePath) {
        List<String> lines = new CopyOnWriteArrayList<>();
        List<String> cmd = List.of(
            config.ffprobe(), "-v", "error",
            "-select_streams", "s",
            "-show_entries", "stream=index:stream_tags=language",
            "-of", "default=noprint_wrappers=1",
            filePath);
        RunningTranscode rt = processRunner.start(cmd, lines::add, l -> {});
        int exit;
        try {
            exit = rt.waitForExit();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProbeResult(false, List.of());
        }
        if (exit != 0) return new ProbeResult(false, List.of());
        return new ProbeResult(true, parse(new ArrayList<>(lines)));
    }

    /** Each `index=` line starts a subtitle stream; the language is the following
     *  `TAG:language=` (or `und` if absent/blank before the next index). */
    static List<String> parse(List<String> lines) {
        List<String> langs = new ArrayList<>();
        boolean inStream = false;
        String pending = null;
        for (String raw : lines) {
            if (raw == null) continue;
            String line = raw.trim();
            if (line.startsWith("index=")) {
                if (inStream) langs.add(pending == null || pending.isBlank() ? "und" : pending);
                inStream = true;
                pending = null;
            } else if (line.startsWith("TAG:language=")) {
                pending = line.substring("TAG:language=".length()).trim().toLowerCase();
            }
        }
        if (inStream) langs.add(pending == null || pending.isBlank() ? "und" : pending);
        return langs;
    }
}
