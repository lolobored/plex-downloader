package org.lolobored.plexdownloader.repository;

import org.lolobored.plexdownloader.model.Episode;
import org.lolobored.plexdownloader.model.Season;
import org.lolobored.plexdownloader.model.TvShow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Aggregate subtitle-missing flag tests.
 * Setup: one show, two seasons.
 *   Season A: one episode with subtitleLangs="," (scanned-empty/missing),
 *             one episode with subtitleLangs=",eng," (has subs).
 *   Season B: one episode with subtitleLangs=",eng," (has subs only).
 *   Extra:    one episode with subtitleLangs=null (unscanned — must NOT count as missing).
 *
 * Expected:
 *   findShowIdsWithMissingSubtitles([show]) → contains show (because Season A has missing)
 *   findSeasonIdsWithMissingSubtitles([A, B]) → contains A only (B has no missing; null doesn't count)
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MissingSubtitlesAggregateTest {

    @Autowired EpisodeRepository episodeRepo;
    @Autowired TvShowRepository showRepo;
    @Autowired SeasonRepository seasonRepo;

    private TvShow show;
    private Season seasonA;
    private Season seasonB;

    @BeforeEach
    void setup() {
        show = savedShow("agg-show-1");
        seasonA = savedSeason(show, "agg-season-A", 1);
        seasonB = savedSeason(show, "agg-season-B", 2);

        // Season A: one missing (scanned-empty), one with subs
        savedEpisode(seasonA, "agg-ep-A1", 1, ",");        // MISSING
        savedEpisode(seasonA, "agg-ep-A2", 2, ",eng,");    // has subs

        // Season B: only has subs, no missing
        savedEpisode(seasonB, "agg-ep-B1", 1, ",eng,");    // has subs

        // Unscanned episode in Season B — must NOT count as missing
        savedEpisode(seasonB, "agg-ep-B2", 2, null);       // unscanned
    }

    @Test
    void findShowIdsWithMissingSubtitles_containsShowWithMissingEpisode() {
        Set<Long> result = episodeRepo.findShowIdsWithMissingSubtitles(List.of(show.getId()));
        assertThat(result).contains(show.getId());
    }

    @Test
    void findSeasonIdsWithMissingSubtitles_containsSeasonAOnly() {
        Set<Long> result = episodeRepo.findSeasonIdsWithMissingSubtitles(
                List.of(seasonA.getId(), seasonB.getId()));
        assertThat(result).containsExactly(seasonA.getId());
        assertThat(result).doesNotContain(seasonB.getId());
    }

    @Test
    void findSeasonIdsWithMissingSubtitles_nullUnscannedDoesNotCountAsMissing() {
        // Season B only has ",eng," and null — null must not trigger missing flag
        Set<Long> result = episodeRepo.findSeasonIdsWithMissingSubtitles(
                List.of(seasonB.getId()));
        assertThat(result).doesNotContain(seasonB.getId());
    }

    @Test
    void findShowIdsWithMissingSubtitles_emptyInput_returnsEmpty() {
        Set<Long> result = episodeRepo.findShowIdsWithMissingSubtitles(List.of());
        assertThat(result).isEmpty();
    }

    @Test
    void findSeasonIdsWithMissingSubtitles_emptyInput_returnsEmpty() {
        Set<Long> result = episodeRepo.findSeasonIdsWithMissingSubtitles(List.of());
        assertThat(result).isEmpty();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

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
