package org.lolobored.plexdownloader.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class QualityProfileTest {

    @Test
    void codec_mapsToFfmpegName() {
        assertThat(QualityProfile.Codec.HEVC_QSV.ffmpegName()).isEqualTo("hevc_qsv");
        assertThat(QualityProfile.Codec.H264_QSV.ffmpegName()).isEqualTo("h264_qsv");
    }

    @Test
    void container_mapsToExtension() {
        assertThat(QualityProfile.Container.MKV.extension()).isEqualTo(".mkv");
        assertThat(QualityProfile.Container.MP4.extension()).isEqualTo(".mp4");
    }

    @Test
    void resolutionCap_mapsToMaxHeight() {
        assertThat(QualityProfile.ResolutionCap.KEEP.maxHeight()).isEqualTo(0);
        assertThat(QualityProfile.ResolutionCap.UHD_4K.maxHeight()).isEqualTo(2160);
        assertThat(QualityProfile.ResolutionCap.P1080.maxHeight()).isEqualTo(1080);
        assertThat(QualityProfile.ResolutionCap.P720.maxHeight()).isEqualTo(720);
    }
}
