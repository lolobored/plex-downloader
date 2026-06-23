package org.lolobored.plexdownloader.transcode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
