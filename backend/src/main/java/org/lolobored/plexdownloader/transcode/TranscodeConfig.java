package org.lolobored.plexdownloader.transcode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves the ffmpeg/ffprobe binaries and the QSV render device.
 *
 * <p>Defaults work for a local dev box (binaries on PATH). The Docker image ships
 * jellyfin-ffmpeg (latest ffmpeg + oneVPL) and overrides the binary paths via the
 * env vars {@code TRANSCODE_FFMPEG_BIN} / {@code TRANSCODE_FFPROBE_BIN}
 * (relaxed-bound to {@code transcode.ffmpeg.bin} / {@code transcode.ffprobe.bin}).
 */
@Component
public class TranscodeConfig {

    private final String ffmpeg;
    private final String ffprobe;
    private final String qsvDevice;

    public TranscodeConfig(
            @Value("${transcode.ffmpeg.bin:ffmpeg}") String ffmpeg,
            @Value("${transcode.ffprobe.bin:ffprobe}") String ffprobe,
            @Value("${transcode.qsv.device:/dev/dri/renderD128}") String qsvDevice) {
        this.ffmpeg = ffmpeg;
        this.ffprobe = ffprobe;
        this.qsvDevice = qsvDevice;
    }

    public String ffmpeg() { return ffmpeg; }
    public String ffprobe() { return ffprobe; }
    public String qsvDevice() { return qsvDevice; }
}
