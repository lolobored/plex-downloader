package org.lolobored.plexdownloader.util;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class SubtitleLangsTest {
    @Test void toCsv_empty_isCommaOnly() {
        assertThat(SubtitleLangs.toCsv(List.of())).isEqualTo(",");
    }
    @Test void toCsv_padsAndLowercases() {
        assertThat(SubtitleLangs.toCsv(List.of("eng", "FRA"))).isEqualTo(",eng,fra,");
    }
    @Test void toCsv_blankBecomesUnd() {
        assertThat(SubtitleLangs.toCsv(java.util.Arrays.asList("eng", "", null))).isEqualTo(",eng,und,und,");
    }
    @Test void fromCsv_roundTrips() {
        assertThat(SubtitleLangs.fromCsv(",eng,fra,")).containsExactly("eng", "fra");
        assertThat(SubtitleLangs.fromCsv(",")).isEmpty();
        assertThat(SubtitleLangs.fromCsv(null)).isEmpty();
    }
    @Test void token_isCommaPadded() {
        assertThat(SubtitleLangs.token("ENG")).isEqualTo(",eng,");
    }
}
