package org.lolobored.plexdownloader.repository;

import org.lolobored.plexdownloader.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for findActiveEpisodeIdsForShow / findActiveEpisodeIdsForSeason.
 * Active statuses are QUEUED, TRANSCODING, DONE. ERROR is not active.
 *
 * {@code @Transactional} rolls back each test so persisted data doesn't pollute
 * the shared H2 database.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DownloadQueueRepositoryActiveEpisodeTest {

    @Autowired DownloadQueueRepository queueRepo;
    @Autowired UserRepository userRepo;
    @Autowired TvShowRepository tvShowRepository;
    @Autowired SeasonRepository seasonRepo;
    @Autowired EpisodeRepository episodeRepo;

    private User savedUser(String plexId) {
        User u = new User();
        u.setPlexAccountId(plexId);
        u.setUsername(plexId);
        return userRepo.save(u);
    }

    private TvShow savedShow(String plexId) {
        TvShow s = new TvShow();
        s.setPlexId(plexId);
        s.setTitle("Show " + plexId);
        s.setSyncedAt(Instant.now());
        return tvShowRepository.save(s);
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

    private Episode savedEpisode(Season season, String plexId, int number) {
        Episode e = new Episode();
        e.setPlexId(plexId);
        e.setSeason(season);
        e.setEpisodeNumber(number);
        e.setTitle("Ep " + number);
        e.setSyncedAt(Instant.now());
        return episodeRepo.save(e);
    }

    private DownloadQueueItem episodeItem(User user, Episode ep, DownloadQueueItem.Status status) {
        DownloadQueueItem i = new DownloadQueueItem();
        i.setUser(user);
        i.setMediaType(DownloadQueueItem.MediaType.EPISODE);
        i.setMediaId(ep.getId());
        i.setTitle(ep.getTitle());
        i.setSourceFilePath("/nas/ep" + ep.getId() + ".avi");
        i.setDestFilePath("/plex/ep" + ep.getId() + ".mkv");
        i.setStatus(status);
        return queueRepo.save(i);
    }

    @Test
    void findActiveEpisodeIdsForShow_includesQueuedTranscodingDoneButNotError() {
        User user = savedUser("active-show-test-user");
        TvShow show = savedShow("show-active-test-1");
        Season season = savedSeason(show, "season-active-test-1", 1);

        Episode epQueued      = savedEpisode(season, "ep-queued-1",      1);
        Episode epTranscoding = savedEpisode(season, "ep-transcoding-1", 2);
        Episode epDone        = savedEpisode(season, "ep-done-1",        3);
        Episode epError       = savedEpisode(season, "ep-error-1",       4);

        episodeItem(user, epQueued,      DownloadQueueItem.Status.QUEUED);
        episodeItem(user, epTranscoding, DownloadQueueItem.Status.TRANSCODING);
        episodeItem(user, epDone,        DownloadQueueItem.Status.DONE);
        episodeItem(user, epError,       DownloadQueueItem.Status.ERROR);

        Set<Long> active = queueRepo.findActiveEpisodeIdsForShow(user.getId(), show.getId());

        assertThat(active).contains(epQueued.getId());
        assertThat(active).contains(epTranscoding.getId());
        assertThat(active).contains(epDone.getId());
        assertThat(active).doesNotContain(epError.getId()); // ERROR is not active
    }

    @Test
    void findActiveEpisodeIdsForSeason_includesQueuedTranscodingDoneButNotError() {
        User user = savedUser("active-season-test-user");
        TvShow show = savedShow("show-active-test-2");
        Season season = savedSeason(show, "season-active-test-2", 1);

        Episode epQueued      = savedEpisode(season, "sep-queued-1",      1);
        Episode epTranscoding = savedEpisode(season, "sep-transcoding-1", 2);
        Episode epDone        = savedEpisode(season, "sep-done-1",        3);
        Episode epError       = savedEpisode(season, "sep-error-1",       4);

        episodeItem(user, epQueued,      DownloadQueueItem.Status.QUEUED);
        episodeItem(user, epTranscoding, DownloadQueueItem.Status.TRANSCODING);
        episodeItem(user, epDone,        DownloadQueueItem.Status.DONE);
        episodeItem(user, epError,       DownloadQueueItem.Status.ERROR);

        Set<Long> active = queueRepo.findActiveEpisodeIdsForSeason(user.getId(), season.getId());

        assertThat(active).contains(epQueued.getId());
        assertThat(active).contains(epTranscoding.getId());
        assertThat(active).contains(epDone.getId());
        assertThat(active).doesNotContain(epError.getId()); // ERROR is not active
    }

    @Test
    void findActiveEpisodeIdsForShow_deduplicatesWhenSameEpisodeQueuedTwice() {
        // Even if there are two queue items for the same episode, the set should not duplicate
        User user = savedUser("dedup-show-test-user");
        TvShow show = savedShow("show-dedup-test-1");
        Season season = savedSeason(show, "season-dedup-test-1", 1);
        Episode ep = savedEpisode(season, "ep-dedup-1", 1);

        episodeItem(user, ep, DownloadQueueItem.Status.QUEUED);
        episodeItem(user, ep, DownloadQueueItem.Status.DONE);

        Set<Long> active = queueRepo.findActiveEpisodeIdsForShow(user.getId(), show.getId());

        // Set deduplicates — the episode ID appears only once
        assertThat(active).containsExactly(ep.getId());
    }
}
