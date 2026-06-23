package org.lolobored.plexdownloader.transcode;

import org.lolobored.plexdownloader.model.QualityProfile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class FfmpegCommandBuilder {

    private final TranscodeConfig config;

    public FfmpegCommandBuilder(TranscodeConfig config) {
        this.config = config;
    }

    public List<String> build(QualityProfile profile, String sourcePath, String destPath, MediaInfo source) {
        List<String> args = new ArrayList<>();
        args.add(config.ffmpeg());
        args.add("-nostdin");
        args.add("-y");

        // QSV via a VAAPI-derived device — the reliable oneVPL path on Intel iHD.
        args.add("-init_hw_device"); args.add("vaapi=va:" + config.qsvDevice());
        args.add("-init_hw_device"); args.add("qsv=hw@va");
        args.add("-hwaccel"); args.add("qsv");
        args.add("-hwaccel_output_format"); args.add("qsv");
        args.add("-i"); args.add(sourcePath);

        // Explicit mapping: primary video (excludes attached cover-art image streams that
        // break the QSV encode), ALL audio, and ALL subtitle streams. '?' = optional.
        args.add("-map"); args.add("0:v:0");
        args.add("-map"); args.add("0:a?");
        args.add("-map"); args.add("0:s?");

        int cap = profile.getResolutionCap().maxHeight();
        if (cap > 0 && source.height() > cap) {
            // vpp_qsv handles 8-bit/10-bit format on-GPU; scale_qsv chokes on 10-bit.
            args.add("-vf"); args.add("vpp_qsv=w=-1:h=" + cap);
        }

        args.add("-c:v"); args.add(profile.getCodec().ffmpegName());
        args.add("-global_quality"); args.add(Integer.toString(profile.getQualityLevel()));

        args.add("-c:a");
        args.add(profile.getAudioMode() == QualityProfile.AudioMode.AAC ? "aac" : "copy");

        // Preserve subtitles. MKV carries any subtitle codec as-is; MP4 only supports
        // mov_text, so convert text subs (image-based subs can't go in MP4 — use MKV).
        args.add("-c:s");
        args.add(profile.getContainer() == QualityProfile.Container.MP4 ? "mov_text" : "copy");

        args.add("-progress"); args.add("pipe:1");
        args.add("-nostats");

        args.add(destPath);
        return args;
    }
}
