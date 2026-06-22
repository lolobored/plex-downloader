package org.lolobored.plexdownloader.transcode;

import org.lolobored.plexdownloader.model.QualityProfile;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class FfmpegCommandBuilderTest {

    private final FfmpegCommandBuilder builder = new FfmpegCommandBuilder();

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

        assertThat(args).containsSubsequence("-hwaccel", "qsv", "-hwaccel_output_format", "qsv");
        assertThat(args).containsSubsequence("-i", "/movies/a.avi");
        assertThat(args).containsSubsequence("-c:v", "hevc_qsv", "-global_quality", "23");
        assertThat(args).containsSubsequence("-c:a", "copy");
        assertThat(args).containsSubsequence("-progress", "pipe:1", "-nostats");
        assertThat(args.get(args.size() - 1)).isEqualTo("/out/a.mkv");
        assertThat(args).doesNotContain("-vf");
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
        assertThat(args).containsSubsequence("-vf", "scale_qsv=-1:720");
    }

    @Test
    void resolutionCapAboveSource_noScaleFilter() {
        QualityProfile p = profile(QualityProfile.Codec.HEVC_QSV, QualityProfile.AudioMode.COPY,
                                   QualityProfile.ResolutionCap.P1080, 23);
        List<String> args = builder.build(p, "/m/d.mkv", "/out/d.mkv", new MediaInfo(60, 1280, 720));
        assertThat(args).doesNotContain("-vf");
    }
}
