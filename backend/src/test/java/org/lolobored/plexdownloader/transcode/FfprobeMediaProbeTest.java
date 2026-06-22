package org.lolobored.plexdownloader.transcode;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class FfprobeMediaProbeTest {

    @Test
    void parsesWidthHeightDuration() {
        MediaInfo info = FfprobeMediaProbe.parse(List.of(
            "width=1920", "height=1080", "duration=120.500000"));
        assertThat(info.width()).isEqualTo(1920);
        assertThat(info.height()).isEqualTo(1080);
        assertThat(info.durationSeconds()).isEqualTo(120.5);
    }

    @Test
    void missingDuration_defaultsToZero() {
        MediaInfo info = FfprobeMediaProbe.parse(List.of("width=640", "height=480"));
        assertThat(info.durationSeconds()).isEqualTo(0.0);
        assertThat(info.width()).isEqualTo(640);
    }

    @Test
    void garbageLinesIgnored() {
        MediaInfo info = FfprobeMediaProbe.parse(List.of("foo=bar", "width=1280", "height=720", ""));
        assertThat(info.width()).isEqualTo(1280);
        assertThat(info.height()).isEqualTo(720);
    }
}
