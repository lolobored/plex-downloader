package org.lolobored.plexdownloader.transcode;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ProgressParserTest {

    private final ProgressParser parser = new ProgressParser();

    @Test
    void outTimeUs_halfway() {
        assertThat(parser.percentFor("out_time_us=30000000", 60.0)).hasValue(50);
    }

    @Test
    void progressEnd_is100() {
        assertThat(parser.percentFor("progress=end", 60.0)).hasValue(100);
    }

    @Test
    void outTimeTimestamp_parsed() {
        // 00:00:30.000000 of a 60s clip = 50%
        assertThat(parser.percentFor("out_time=00:00:30.000000", 60.0)).hasValue(50);
    }

    @Test
    void overshoot_clampedTo100() {
        assertThat(parser.percentFor("out_time_us=120000000", 60.0)).hasValue(100);
    }

    @Test
    void unrelatedLine_empty() {
        assertThat(parser.percentFor("bitrate= 1234.5kbits/s", 60.0)).isEmpty();
    }

    @Test
    void zeroDuration_timeLineEmpty() {
        assertThat(parser.percentFor("out_time_us=30000000", 0.0)).isEmpty();
    }
}
