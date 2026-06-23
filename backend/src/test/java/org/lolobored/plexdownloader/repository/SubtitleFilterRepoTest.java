package org.lolobored.plexdownloader.repository;

import org.lolobored.plexdownloader.model.Episode;
import org.lolobored.plexdownloader.model.Movie;
import org.lolobored.plexdownloader.model.Season;
import org.lolobored.plexdownloader.model.TvShow;
import org.lolobored.plexdownloader.util.SubtitleLangs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DB-side subtitle filter predicate tests.
 * Four subtitle_langs values: "," (scanned-empty), ",eng," (has eng), ",enga," (tricky),
 * null (unscanned/unknown).
 *
 * none       → only ","
 * hasLang eng → only ",eng," — NOT ",enga," (comma-padding guard)
 * missingLang eng → "," and ",enga," — NOT null (unknown excluded)
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SubtitleFilterRepoTest {

    @Autowired MovieRepository movieRepo;
    @Autowired EpisodeRepository episodeRepo;
    @Autowired TvShowRepository showRepo;
    @Autowired SeasonRepository seasonRepo;

    private Movie mNone;    // ","
    private Movie mEng;     // ",eng,"
    private Movie mEnga;    // ",enga,"
    private Movie mNull;    // null (unscanned)

    @BeforeEach
    void persist() {
        mNone = savedMovie("m-none",  ",");
        mEng  = savedMovie("m-eng",   ",eng,");
        mEnga = savedMovie("m-enga",  ",enga,");
        mNull = savedMovie("m-null",  null);
    }

    // ── Movie filter tests ──────────────────────────────────────────────────

    @Test
    void movies_none_returnsOnlyScannedEmpty() {
        List<Movie> results = movieRepo.findFilteredBySubtitles(true, null, null);
        assertThat(results).extracting(Movie::getId)
            .containsExactlyInAnyOrder(mNone.getId())
            .doesNotContain(mEng.getId(), mEnga.getId(), mNull.getId());
    }

    @Test
    void movies_hasLang_eng_returnsOnlyEngNotEnga() {
        String token = SubtitleLangs.token("eng");
        List<Movie> results = movieRepo.findFilteredBySubtitles(false, token, null);
        assertThat(results).extracting(Movie::getId)
            .containsExactlyInAnyOrder(mEng.getId())
            .doesNotContain(mNone.getId(), mEnga.getId(), mNull.getId());
    }

    @Test
    void movies_missingLang_eng_returnsScannedWithoutEng_excludesUnknown() {
        String token = SubtitleLangs.token("eng");
        List<Movie> results = movieRepo.findFilteredBySubtitles(false, null, token);
        assertThat(results).extracting(Movie::getId)
            .containsExactlyInAnyOrder(mNone.getId(), mEnga.getId())
            .doesNotContain(mEng.getId(), mNull.getId());
    }

    @Test
    void movies_noFilters_returnsAll() {
        // When all params are "absent" (none=false, has=null, missing=null), all rows returned
        List<Movie> results = movieRepo.findFilteredBySubtitles(false, null, null);
        assertThat(results).extracting(Movie::getId)
            .contains(mNone.getId(), mEng.getId(), mEnga.getId(), mNull.getId());
    }

    // ── Episode filter tests ─────────────────────────────────────────────────

    @Test
    void episodes_none_returnsOnlyScannedEmpty() {
        TvShow show = savedShow("show-sub-test-1");
        Season season = savedSeason(show, "season-sub-test-1", 1);
        Episode epNone = savedEpisode(season, "ep-sub-none-1", 1, ",");
        Episode epEng  = savedEpisode(season, "ep-sub-eng-1",  2, ",eng,");
        Episode epNull = savedEpisode(season, "ep-sub-null-1", 3, null);

        List<Episode> results = episodeRepo.findFilteredBySubtitles(true, null, null);
        assertThat(results).extracting(Episode::getId)
            .contains(epNone.getId())
            .doesNotContain(epEng.getId(), epNull.getId());
    }

    @Test
    void episodes_hasLang_eng_returnsOnlyEng() {
        TvShow show = savedShow("show-sub-test-2");
        Season season = savedSeason(show, "season-sub-test-2", 1);
        Episode epEng  = savedEpisode(season, "ep-sub-eng-2",  1, ",eng,");
        Episode epEnga = savedEpisode(season, "ep-sub-enga-2", 2, ",enga,");
        Episode epNull = savedEpisode(season, "ep-sub-null-2", 3, null);

        String token = SubtitleLangs.token("eng");
        List<Episode> results = episodeRepo.findFilteredBySubtitles(false, token, null);
        assertThat(results).extracting(Episode::getId)
            .containsExactlyInAnyOrder(epEng.getId())
            .doesNotContain(epEnga.getId(), epNull.getId());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Movie savedMovie(String plexId, String subtitleLangs) {
        Movie m = new Movie();
        m.setPlexId(plexId);
        m.setTitle("Movie " + plexId);
        m.setSyncedAt(Instant.now());
        m.setFilePath("/nas/" + plexId + ".mkv");
        m.setSubtitleLangs(subtitleLangs);
        return movieRepo.save(m);
    }

    private TvShow savedShow(String plexId) {
        TvShow s = new TvShow();
        s.setPlexId(plexId);
        s.setTitle("Show " + plexId);
        s.setSyncedAt(Instant.now());
        return showRepo.save(s);
    }

    private Season savedSeason(TvShow show, String plexId, int number) {
        Season s = new Season();
        s.setPlexId(plexId);
        s.setShow(show);
        s.setSeasonNumber(number);
        s.setTitle("Season " + number);
        s.setSyncedAt(Instant.now());
        return seasonRepo.save(s);
    }

    private Episode savedEpisode(Season season, String plexId, int number, String subtitleLangs) {
        Episode e = new Episode();
        e.setPlexId(plexId);
        e.setSeason(season);
        e.setEpisodeNumber(number);
        e.setTitle("Ep " + number);
        e.setSyncedAt(Instant.now());
        e.setSubtitleLangs(subtitleLangs);
        return episodeRepo.save(e);
    }
}
