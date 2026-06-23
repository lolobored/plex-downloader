package org.lolobored.plexdownloader.transcode;

import org.lolobored.plexdownloader.model.QualityProfile;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class FfmpegCommandBuilderTest {

    private final TranscodeConfig config = new TranscodeConfig("ffmpeg", "ffprobe", "/dev/dri/renderD128");
    private final FfmpegCommandBuilder builder = new FfmpegCommandBuilder(config);

    private QualityProfile profile(QualityProfile.Codec codec, QualityProfile.AudioMode audio,
                                   QualityProfile.ResolutionCap cap, int quality) {
        QualityProfile p = new QualityProfile();
        p.setCodec(codec); p.setAudioMode(audio); p.setResolutionCap(cap); p.setQualityLevel(quality);
        return p;
    }

    @Test
    void hevcCopyKeep_buildsExpectedArgs() {
        QualityProfile p = profile(QualityProfile.Codec.HEVC_QSV, QualityProfile.AudioMode.COPY,
                                   QualityProfile.ResolutionCap.KEEP, 23);
        List<String> args = builder.build(p, "/movies/a.avi", "/out/a.mkv", new MediaInfo(120, 1920, 1080));

        assertThat(args.get(0)).isEqualTo("ffmpeg");
        assertThat(args).containsSubsequence(
            "-init_hw_device", "vaapi=va:/dev/dri/renderD128",
            "-init_hw_device", "qsv=hw@va");
        assertThat(args).containsSubsequence("-hwaccel", "qsv", "-hwaccel_output_format", "qsv");
        assertThat(args).containsSubsequence("-i", "/movies/a.avi");
        assertThat(args).containsSubsequence("-map", "0:v:0", "-map", "0:a?", "-map", "0:s?");
        assertThat(args).containsSubsequence("-c:v", "hevc_qsv", "-global_quality", "23");
        assertThat(args).containsSubsequence("-c:a", "copy");
        assertThat(args).containsSubsequence("-c:s", "copy"); // MKV preserves subs as-is
        assertThat(args).containsSubsequence("-progress", "pipe:1", "-nostats");
        assertThat(args.get(args.size() - 1)).isEqualTo("/out/a.mkv");
        assertThat(args).doesNotContain("-vf");
    }

    @Test
    void mp4Container_convertsSubsToMovText() {
        QualityProfile p = profile(QualityProfile.Codec.HEVC_QSV, QualityProfile.AudioMode.COPY,
                                   QualityProfile.ResolutionCap.KEEP, 23);
        p.setContainer(QualityProfile.Container.MP4);
        List<String> args = builder.build(p, "/m/e.mkv", "/out/e.mp4", new MediaInfo(60, 1280, 720));
        assertThat(args).containsSubsequence("-map", "0:v:0", "-map", "0:a?", "-map", "0:s?");
        assertThat(args).containsSubsequence("-c:s", "mov_text"); // MP4 only supports mov_text
    }

    @Test
    void h264Aac_setsCodecAndAac() {
        QualityProfile p = profile(QualityProfile.Codec.H264_QSV, QualityProfile.AudioMode.AAC,
                                   QualityProfile.ResolutionCap.KEEP, 20);
        List<String> args = builder.build(p, "/m/b.mkv", "/out/b.mp4", new MediaInfo(60, 1280, 720));
        assertThat(args).containsSubsequence("-c:v", "h264_qsv", "-global_quality", "20");
        assertThat(args).containsSubsequence("-c:a", "aac");
    }

    @Test
    void resolutionCapBelowSource_addsScaleFilter() {
        QualityProfile p = profile(QualityProfile.Codec.HEVC_QSV, QualityProfile.AudioMode.COPY,
                                   QualityProfile.ResolutionCap.P720, 23);
        List<String> args = builder.build(p, "/m/c.mkv", "/out/c.mkv", new MediaInfo(60, 1920, 1080));
        assertThat(args).containsSubsequence("-vf", "vpp_qsv=w=-1:h=720");
    }

    @Test
    void resolutionCapAboveSource_noScaleFilter() {
        QualityProfile p = profile(QualityProfile.Codec.HEVC_QSV, QualityProfile.AudioMode.COPY,
                                   QualityProfile.ResolutionCap.P1080, 23);
        List<String> args = builder.build(p, "/m/d.mkv", "/out/d.mkv", new MediaInfo(60, 1280, 720));
        assertThat(args).doesNotContain("-vf");
    }
}
