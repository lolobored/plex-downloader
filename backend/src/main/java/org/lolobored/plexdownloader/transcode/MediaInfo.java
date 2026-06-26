package org.lolobored.plexdownloader.transcode;

import java.util.List;

/**
 * Probed source media facts.
 *
 * @param subtitleCodecs codec_name of each subtitle stream, in stream order (e.g. "mov_text",
 *                       "subrip"). Drives whether MP4 output can COPY a sub stream or must
 *                       re-encode it to mov_text. Empty when unknown / no subtitles.
 */
public record MediaInfo(double durationSeconds, int width, int height, List<String> subtitleCodecs) {
    public MediaInfo(double durationSeconds, int width, int height) {
        this(durationSeconds, width, height, List.of());
    }
}
