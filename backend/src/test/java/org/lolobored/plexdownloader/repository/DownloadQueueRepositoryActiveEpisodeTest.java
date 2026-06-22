package org.lolobored.plexdownloader.repository;

import org.lolobored.plexdownloader.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for Fix 2: findActiveEpisodeIdsForShow / findActiveEpisodeIdsForSeason
 * must include FETCHING and COPYING statuses so mid-fetch/mid-copy episodes are not
 * re-enqueued by subscription sync.
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext
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
    void findActiveEpisodeIdsForShow_includesFetchingAndCopying() {
        User user = savedUser("active-show-test-user");
        TvShow show = savedShow("show-active-test-1");
        Season season = savedSeason(show, "season-active-test-1", 1);

        Episode epQueued     = savedEpisode(season, "ep-queued-1",     1);
        Episode epFetching   = savedEpisode(season, "ep-fetching-1",   2);
        Episode epTranscoding = savedEpisode(season, "ep-transcoding-1", 3);
        Episode epCopying    = savedEpisode(season, "ep-copying-1",    4);
        Episode epDone       = savedEpisode(season, "ep-done-1",       5);
        Episode epError      = savedEpisode(season, "ep-error-1",      6);

        episodeItem(user, epQueued,      DownloadQueueItem.Status.QUEUED);
        episodeItem(user, epFetching,    DownloadQueueItem.Status.FETCHING);
        episodeItem(user, epTranscoding, DownloadQueueItem.Status.TRANSCODING);
        episodeItem(user, epCopying,     DownloadQueueItem.Status.COPYING);
        episodeItem(user, epDone,        DownloadQueueItem.Status.DONE);
        episodeItem(user, epError,       DownloadQueueItem.Status.ERROR);

        Set<Long> active = queueRepo.findActiveEpisodeIdsForShow(user.getId(), show.getId());

        assertThat(active).contains(epQueued.getId());
        assertThat(active).contains(epFetching.getId());     // Fix 2: must be included
        assertThat(active).contains(epTranscoding.getId());
        assertThat(active).contains(epCopying.getId());      // Fix 2: must be included
        assertThat(active).contains(epDone.getId());
        assertThat(active).doesNotContain(epError.getId()); // ERROR is not active
    }

    @Test
    void findActiveEpisodeIdsForSeason_includesFetchingAndCopying() {
        User user = savedUser("active-season-test-user");
        TvShow show = savedShow("show-active-test-2");
        Season season = savedSeason(show, "season-active-test-2", 1);

        Episode epQueued     = savedEpisode(season, "sep-queued-1",     1);
        Episode epFetching   = savedEpisode(season, "sep-fetching-1",   2);
        Episode epTranscoding = savedEpisode(season, "sep-transcoding-1", 3);
        Episode epCopying    = savedEpisode(season, "sep-copying-1",    4);
        Episode epDone       = savedEpisode(season, "sep-done-1",       5);
        Episode epError      = savedEpisode(season, "sep-error-1",      6);

        episodeItem(user, epQueued,      DownloadQueueItem.Status.QUEUED);
        episodeItem(user, epFetching,    DownloadQueueItem.Status.FETCHING);
        episodeItem(user, epTranscoding, DownloadQueueItem.Status.TRANSCODING);
        episodeItem(user, epCopying,     DownloadQueueItem.Status.COPYING);
        episodeItem(user, epDone,        DownloadQueueItem.Status.DONE);
        episodeItem(user, epError,       DownloadQueueItem.Status.ERROR);

        Set<Long> active = queueRepo.findActiveEpisodeIdsForSeason(user.getId(), season.getId());

        assertThat(active).contains(epQueued.getId());
        assertThat(active).contains(epFetching.getId());     // Fix 2: must be included
        assertThat(active).contains(epTranscoding.getId());
        assertThat(active).contains(epCopying.getId());      // Fix 2: must be included
        assertThat(active).contains(epDone.getId());
        assertThat(active).doesNotContain(epError.getId()); // ERROR is not active
    }
}
