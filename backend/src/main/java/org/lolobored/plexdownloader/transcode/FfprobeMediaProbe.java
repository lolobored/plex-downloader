package org.lolobored.plexdownloader.transcode;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
@RequiredArgsConstructor
public class FfprobeMediaProbe implements MediaProbe {

    private final ProcessRunner processRunner;
    private final TranscodeConfig config;

    @Override
    public MediaInfo probe(String sourcePath) {
        List<String> lines = new CopyOnWriteArrayList<>();
        List<String> cmd = List.of(
            config.ffprobe(), "-v", "error",
            "-select_streams", "v:0",
            "-show_entries", "stream=width,height",
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1",
            sourcePath);
        RunningTranscode rt = processRunner.start(cmd, lines::add, l -> {});
        try {
            rt.waitForExit();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        MediaInfo base = parse(new ArrayList<>(lines));
        return new MediaInfo(base.durationSeconds(), base.width(), base.height(),
            probeSubtitleCodecs(sourcePath));
    }

    /** codec_name of each subtitle stream, in stream order. Empty on failure / no subtitles. */
    private List<String> probeSubtitleCodecs(String sourcePath) {
        List<String> lines = new CopyOnWriteArrayList<>();
        List<String> cmd = List.of(
            config.ffprobe(), "-v", "error",
            "-select_streams", "s",
            "-show_entries", "stream=codec_name",
            "-of", "default=noprint_wrappers=1:nokey=1",
            sourcePath);
        RunningTranscode rt = processRunner.start(cmd, lines::add, l -> {});
        try {
            rt.waitForExit();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        List<String> codecs = new ArrayList<>();
        for (String line : lines) {
            if (line != null && !line.isBlank()) codecs.add(line.trim());
        }
        return codecs;
    }

    static MediaInfo parse(List<String> lines) {
        int width = 0, height = 0;
        double duration = 0.0;
        for (String line : lines) {
            if (line == null) continue;
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = line.substring(0, eq).trim();
            String val = line.substring(eq + 1).trim();
            try {
                switch (key) {
                    case "width"    -> width = Integer.parseInt(val);
                    case "height"   -> height = Integer.parseInt(val);
                    case "duration" -> duration = Double.parseDouble(val);
                    default -> { /* ignore */ }
                }
            } catch (NumberFormatException ignored) { /* skip */ }
        }
        return new MediaInfo(duration, width, height);
    }
}
