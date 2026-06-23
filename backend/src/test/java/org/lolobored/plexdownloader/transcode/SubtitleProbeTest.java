package org.lolobored.plexdownloader.transcode;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class SubtitleProbeTest {
    @Test void parse_twoStreamsWithLangs() {
        List<String> out = SubtitleProbe.parse(List.of(
            "index=2", "TAG:language=eng",
            "index=3", "TAG:language=fra"));
        assertThat(out).containsExactly("eng", "fra");
    }
    @Test void parse_untaggedStreamBecomesUnd() {
        List<String> out = SubtitleProbe.parse(List.of("index=2"));
        assertThat(out).containsExactly("und");
    }
    @Test void parse_emptyLangBecomesUnd() {
        List<String> out = SubtitleProbe.parse(List.of("index=2", "TAG:language="));
        assertThat(out).containsExactly("und");
    }
    @Test void parse_noSubtitleStreams_empty() {
        assertThat(SubtitleProbe.parse(List.of())).isEmpty();
    }
}
