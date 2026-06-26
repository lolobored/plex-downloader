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

        // Explicit mapping: primary REAL video via the capital-V specifier, which excludes
        // attached pictures / thumbnails / cover art (e.g. an embedded mjpeg poster). The
        // lowercase '0:v:0' is positional and would select the cover-art still when it is the
        // first video stream, feeding a bogus frame to hevc_qsv (Invalid FrameType:0). Plus
        // ALL audio and ALL subtitle streams. '?' = optional.
        args.add("-map"); args.add("0:V:0");
        args.add("-map"); args.add("0:a?");
        args.add("-map"); args.add("0:s?");

        // ALWAYS route video through vpp_qsv, even when not downscaling. With the full-GPU
        // passthrough (-hwaccel_output_format qsv), raw decoder surfaces carry picture types
        // hevc_qsv rejects (Invalid FrameType:0 -> "Error submitting video frame to the
        // encoder" -> exit 183 / AVERROR_INVALIDDATA -1094995529). A VPP pass emits fresh
        // surfaces with valid frame metadata, decoupling decode from encode. vpp_qsv also
        // handles 8-bit/10-bit format on-GPU; scale_qsv chokes on 10-bit.
        int cap = profile.getResolutionCap().maxHeight();
        if (cap > 0 && source.height() > cap) {
            args.add("-vf"); args.add("vpp_qsv=w=-1:h=" + cap);
        } else {
            // Identity resize (same dimensions) — still goes through the VPP pipeline.
            args.add("-vf"); args.add("vpp_qsv=w=" + source.width() + ":h=" + source.height());
        }

        // Force constant frame rate so the encoder regenerates clean, monotonic timestamps.
        // Some sources carry jumpy/garbage input timestamps that survive to the muxer as
        // "Non-monotonic DTS" plus an absurd packet duration (e.g. 2303893000), which the mp4
        // muxer rejects with "Application provided duration ... is invalid" -> -22 (EINVAL) ->
        // "Conversion failed!" (ffmpeg exit 234). CFR emits uniform frame durations and fixes it.
        args.add("-fps_mode"); args.add("cfr");

        args.add("-c:v"); args.add(profile.getCodec().ffmpegName());
        args.add("-global_quality"); args.add(Integer.toString(profile.getQualityLevel()));

        args.add("-c:a");
        args.add(profile.getAudioMode() == QualityProfile.AudioMode.AAC ? "aac" : "copy");

        // Preserve subtitles. MKV carries any subtitle codec as-is (copy). MP4 only supports
        // mov_text, so text subs must be converted — EXCEPT a source stream that is ALREADY
        // mov_text, which we COPY per-stream. Re-encoding mov_text -> mov_text makes ffmpeg
        // regenerate trailing gap-fill samples whose duration can exceed INT_MAX once rescaled
        // to the mp4 microsecond timescale; movenc then rejects them ("Application provided
        // duration ... is invalid" -> -22 / exit 234). Copying keeps the source track timescale
        // and sidesteps the overflow. Image-based subs still can't go in MP4 — use MKV.
        if (profile.getContainer() != QualityProfile.Container.MP4) {
            args.add("-c:s"); args.add("copy");
        } else if (source.subtitleCodecs().isEmpty()) {
            // No probed sub codecs (or none present): keep the historical default.
            args.add("-c:s"); args.add("mov_text");
        } else {
            List<String> subs = source.subtitleCodecs();
            for (int i = 0; i < subs.size(); i++) {
                args.add("-c:s:" + i);
                args.add("mov_text".equals(subs.get(i)) ? "copy" : "mov_text");
            }
        }

        // Disable the interleave-delta cap so the muxer never bails when one stream's
        // timestamps drift far from another's (defensive belt-and-braces with -fps_mode cfr).
        args.add("-max_interleave_delta"); args.add("0");

        args.add("-progress"); args.add("pipe:1");
        args.add("-nostats");

        args.add(destPath);
        return args;
    }
}
