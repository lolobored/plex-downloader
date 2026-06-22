// backend/src/test/java/org/lolobored/plexdownloader/service/PlaylistSyncServiceTest.java
package org.lolobored.plexdownloader.service;

import org.lolobored.plexdownloader.client.PlexMediaServerClient;
import org.lolobored.plexdownloader.client.dto.PlexItem;
import org.lolobored.plexdownloader.client.dto.PlexPlaylist;
import org.lolobored.plexdownloader.model.*;
import org.lolobored.plexdownloader.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlaylistSyncServiceTest {

    @Mock PlexMediaServerClient plexClient;
    @Mock PlaylistRepository playlistRepo;
    @Mock PlaylistItemRepository itemRepo;
    @Mock PlaylistSubscriptionRepository subRepo;
    @Mock MovieRepository movieRepo;
    @Mock EpisodeRepository episodeRepo;
    @Mock DownloadQueueRepository queueRepo;
    @Mock DownloadService downloadService;
    @Mock org.lolobored.plexdownloader.transcode.TranscodeService transcodeService;
    @InjectMocks PlaylistSyncService service;

    private PlexPlaylist plexPlaylist(String key) {
        PlexPlaylist p = new PlexPlaylist();
        p.setRatingKey(key); p.setTitle("Test"); p.setPlaylistType("video");
        return p;
    }

    private PlexPlaylist plexPlaylistWithLeafCount(String key, int leafCount) {
        PlexPlaylist p = plexPlaylist(key);
        p.setLeafCount(leafCount);
        return p;
    }

    private Playlist localPlaylist(Long id, String plexId) {
        Playlist p = new Playlist(); p.setId(id); p.setPlexId(plexId);
        return p;
    }

    private PlexItem plexItem(String key, String type) {
        PlexItem i = new PlexItem(); i.setRatingKey(key); i.setType(type);
        return i;
    }

    @Test
    void syncAll_queuesAddedMovieForSubscriber() {
        when(plexClient.getPlaylists()).thenReturn(List.of(plexPlaylist("pl1")));
        Playlist local = localPlaylist(10L, "pl1");
        when(playlistRepo.findByPlexId("pl1")).thenReturn(Optional.of(local));
        when(playlistRepo.save(any())).thenReturn(local);
        when(itemRepo.findByPlaylistIdOrderByOrdinalAsc(10L)).thenReturn(List.of());
        when(plexClient.getPlaylistItems("pl1")).thenReturn(List.of(plexItem("m1", "movie")));

        User user = new User(); user.setId(1L);
        PlaylistSubscription sub = new PlaylistSubscription(); sub.setUser(user);
        when(subRepo.findByPlaylistIdWithUser(10L)).thenReturn(List.of(sub));

        Movie m = new Movie(); m.setId(100L);
        when(movieRepo.findByPlexId("m1")).thenReturn(Optional.of(m));
        when(queueRepo.existsByUser_IdAndMediaTypeAndMediaId(1L, DownloadQueueItem.MediaType.MOVIE, 100L))
            .thenReturn(false);
        when(downloadService.enqueueMovie(100L, user, 10L)).thenReturn(List.of(1L));
        when(playlistRepo.findAll()).thenReturn(List.of(local));

        service.syncAll();

        verify(downloadService).enqueueMovie(100L, user, 10L);
        verify(itemRepo).save(argThat(i -> "m1".equals(i.getPlexId()) && "MOVIE".equals(i.getMediaType())));
    }

    @Test
    void enqueueForSubscription_passesPlaylistIdToEnqueue() {
        PlaylistItem pi = new PlaylistItem();
        pi.setPlexId("ep1");
        pi.setMediaType("EPISODE");

        when(itemRepo.findByPlaylistIdOrderByOrdinalAsc(10L)).thenReturn(List.of(pi));

        Episode ep = new Episode(); ep.setId(200L);
        when(episodeRepo.findByPlexId("ep1")).thenReturn(Optional.of(ep));
        when(queueRepo.existsByUser_IdAndMediaTypeAndMediaId(anyLong(),
            eq(DownloadQueueItem.MediaType.EPISODE), eq(200L))).thenReturn(false);

        User user = new User(); user.setId(1L);
        when(downloadService.enqueueEpisode(200L, user, 10L)).thenReturn(List.of(5L));

        service.enqueueForSubscription(10L, user);

        verify(downloadService).enqueueEpisode(200L, user, 10L);
    }

    @Test
    void syncAll_cancelsRemovedItem_deletesFile(@TempDir Path tempDir) throws Exception {
        when(plexClient.getPlaylists()).thenReturn(List.of(plexPlaylist("pl1")));
        Playlist local = localPlaylist(10L, "pl1");
        when(playlistRepo.findByPlexId("pl1")).thenReturn(Optional.of(local));
        when(playlistRepo.save(any())).thenReturn(local);

        PlaylistItem oldItem = new PlaylistItem();
        oldItem.setPlexId("m1"); oldItem.setMediaType("MOVIE"); oldItem.setPlaylistId(10L);
        when(itemRepo.findByPlaylistIdOrderByOrdinalAsc(10L)).thenReturn(List.of(oldItem));
        when(plexClient.getPlaylistItems("pl1")).thenReturn(List.of()); // item removed

        User user = new User(); user.setId(1L);
        PlaylistSubscription sub = new PlaylistSubscription(); sub.setUser(user);
        when(subRepo.findByPlaylistIdWithUser(10L)).thenReturn(List.of(sub));

        Path destFile = tempDir.resolve("movie.mkv");
        Files.writeString(destFile, "data");
        Movie m = new Movie(); m.setId(100L);
        when(movieRepo.findByPlexId("m1")).thenReturn(Optional.of(m));
        DownloadQueueItem qi = new DownloadQueueItem();
        qi.setDestFilePath(destFile.toString());
        when(queueRepo.findByUser_IdAndMediaTypeAndMediaId(1L, DownloadQueueItem.MediaType.MOVIE, 100L))
            .thenReturn(Optional.of(qi));

        service.syncAll();

        verify(itemRepo).deleteByPlaylistIdAndPlexId(10L, "m1");
        // cancelItem now delegates file delete + dir prune + row removal to DownloadService.
        verify(downloadService).doCancelItem(qi);
    }

    @Test
    void syncAll_skipsEnqueue_whenAlreadyQueued() {
        when(plexClient.getPlaylists()).thenReturn(List.of(plexPlaylist("pl1")));
        Playlist local = localPlaylist(10L, "pl1");
        when(playlistRepo.findByPlexId("pl1")).thenReturn(Optional.of(local));
        when(playlistRepo.save(any())).thenReturn(local);
        when(itemRepo.findByPlaylistIdOrderByOrdinalAsc(10L)).thenReturn(List.of());
        when(plexClient.getPlaylistItems("pl1")).thenReturn(List.of(plexItem("m1", "movie")));

        User user = new User(); user.setId(1L);
        PlaylistSubscription sub = new PlaylistSubscription(); sub.setUser(user);
        when(subRepo.findByPlaylistIdWithUser(10L)).thenReturn(List.of(sub));

        Movie m = new Movie(); m.setId(100L);
        when(movieRepo.findByPlexId("m1")).thenReturn(Optional.of(m));
        when(queueRepo.existsByUser_IdAndMediaTypeAndMediaId(1L, DownloadQueueItem.MediaType.MOVIE, 100L)).thenReturn(true);
        when(playlistRepo.findAll()).thenReturn(List.of(local));

        service.syncAll();

        verify(downloadService, never()).enqueueMovie(anyLong(), any(), any());
    }

    @Test
    void syncAll_skipsItem_whenNotInLibrary() {
        when(plexClient.getPlaylists()).thenReturn(List.of(plexPlaylist("pl1")));
        Playlist local = localPlaylist(10L, "pl1");
        when(playlistRepo.findByPlexId("pl1")).thenReturn(Optional.of(local));
        when(playlistRepo.save(any())).thenReturn(local);
        when(itemRepo.findByPlaylistIdOrderByOrdinalAsc(10L)).thenReturn(List.of());
        when(plexClient.getPlaylistItems("pl1")).thenReturn(List.of(plexItem("unknown", "movie")));

        User user = new User(); user.setId(1L);
        PlaylistSubscription sub = new PlaylistSubscription(); sub.setUser(user);
        when(subRepo.findByPlaylistIdWithUser(10L)).thenReturn(List.of(sub));
        when(movieRepo.findByPlexId("unknown")).thenReturn(Optional.empty());
        when(playlistRepo.findAll()).thenReturn(List.of(local));

        service.syncAll();

        verify(downloadService, never()).enqueueMovie(anyLong(), any(), any());
    }

    @Test
    void syncAll_doesNotThrow_whenPlexUnreachable() {
        when(plexClient.getPlaylists()).thenThrow(new RuntimeException("Plex unreachable"));
        service.syncAll();  // must not throw
    }

    @Test
    void syncAll_removesOrphanPlaylist_whenDeletedFromPlex() {
        // Plex returns only pl1, not pl2
        when(plexClient.getPlaylists()).thenReturn(List.of(plexPlaylist("pl1")));

        // pl1 exists locally
        Playlist pl1 = localPlaylist(10L, "pl1");
        when(playlistRepo.findByPlexId("pl1")).thenReturn(Optional.of(pl1));
        when(playlistRepo.save(any())).thenReturn(pl1);
        when(itemRepo.findByPlaylistIdOrderByOrdinalAsc(10L)).thenReturn(List.of());
        when(plexClient.getPlaylistItems("pl1")).thenReturn(List.of());
        when(subRepo.findByPlaylistIdWithUser(10L)).thenReturn(List.of());

        // pl2 exists only locally (orphan — deleted from Plex)
        Playlist pl2 = localPlaylist(20L, "pl2");
        pl2.setTitle("Orphan");
        when(playlistRepo.findAll()).thenReturn(List.of(pl1, pl2));

        service.syncAll();

        verify(itemRepo).deleteAllByPlaylistId(20L);
        verify(subRepo).deleteByPlaylistId(20L);
        verify(playlistRepo).delete(pl2);
        verify(playlistRepo, never()).delete(pl1);
    }

    @Test
    void enqueueForSubscription_queuesAllCurrentItems() {
        PlaylistItem pi1 = new PlaylistItem(); pi1.setPlexId("m1"); pi1.setMediaType("MOVIE");
        PlaylistItem pi2 = new PlaylistItem(); pi2.setPlexId("e1"); pi2.setMediaType("EPISODE");
        when(itemRepo.findByPlaylistIdOrderByOrdinalAsc(10L)).thenReturn(List.of(pi1, pi2));

        User user = new User(); user.setId(1L);
        Movie m = new Movie(); m.setId(100L);
        Episode ep = new Episode(); ep.setId(200L);
        when(movieRepo.findByPlexId("m1")).thenReturn(Optional.of(m));
        when(episodeRepo.findByPlexId("e1")).thenReturn(Optional.of(ep));
        when(queueRepo.existsByUser_IdAndMediaTypeAndMediaId(1L, DownloadQueueItem.MediaType.MOVIE, 100L)).thenReturn(false);
        when(queueRepo.existsByUser_IdAndMediaTypeAndMediaId(1L, DownloadQueueItem.MediaType.EPISODE, 200L)).thenReturn(false);
        when(downloadService.enqueueMovie(100L, user, 10L)).thenReturn(List.of(1L));
        when(downloadService.enqueueEpisode(200L, user, 10L)).thenReturn(List.of(2L));

        service.enqueueForSubscription(10L, user);

        verify(downloadService).enqueueMovie(100L, user, 10L);
        verify(downloadService).enqueueEpisode(200L, user, 10L);
    }

    @Test
    void countQueuedForUser_returnsCount() {
        DownloadQueueItem i1 = new DownloadQueueItem(); i1.setId(1L);
        DownloadQueueItem i2 = new DownloadQueueItem(); i2.setId(2L);
        when(queueRepo.findAllByUserIdAndPlaylistId(1L, 5L)).thenReturn(List.of(i1, i2));

        int count = service.countQueuedForUser(1L, 5L);

        assertThat(count).isEqualTo(2);
    }

    @Test
    void cancelAllForUser_cancelsNonTranscodingItems() {
        DownloadQueueItem queued = new DownloadQueueItem();
        queued.setId(1L); queued.setStatus(DownloadQueueItem.Status.QUEUED);
        when(queueRepo.findAllByUserIdAndPlaylistId(1L, 5L)).thenReturn(List.of(queued));

        int result = service.cancelAllForUser(1L, 5L);

        assertThat(result).isEqualTo(1);
        verify(downloadService).doCancelItem(queued);
        verify(queueRepo, never()).save(any());
    }

    @Test
    void cancelAllForUser_cancelsInFlightTranscode() {
        DownloadQueueItem transcoding = new DownloadQueueItem();
        transcoding.setId(2L); transcoding.setStatus(DownloadQueueItem.Status.TRANSCODING);
        when(queueRepo.findAllByUserIdAndPlaylistId(1L, 5L)).thenReturn(List.of(transcoding));

        service.cancelAllForUser(1L, 5L);

        verify(transcodeService).cancel(2L);
        verify(downloadService).doCancelItem(transcoding);
    }

    @Test
    void cancelAllForUser_returnsZeroWhenEmpty() {
        when(queueRepo.findAllByUserIdAndPlaylistId(1L, 5L)).thenReturn(List.of());

        int result = service.cancelAllForUser(1L, 5L);

        assertThat(result).isEqualTo(0);
        verify(downloadService, never()).doCancelItem(any());
    }

    // --- Fix #64: leafCount guard tests ---

    /**
     * Guard test (the data-loss net): when Plex reports leafCount=5 but the fetch returns
     * fewer items (partial/failed fetch), no removals or cancels must happen.
     */
    @Test
    void syncPlaylist_partialFetch_skipsRemovalsAndCancels() {
        // Playlist Plex says has 5 items
        PlexPlaylist pp = plexPlaylistWithLeafCount("pl1", 5);
        when(plexClient.getPlaylists()).thenReturn(List.of(pp));

        Playlist local = localPlaylist(10L, "pl1");
        when(playlistRepo.findByPlexId("pl1")).thenReturn(Optional.of(local));
        when(playlistRepo.save(any())).thenReturn(local);

        // We have 3 items locally
        PlaylistItem item1 = new PlaylistItem(); item1.setPlexId("m1"); item1.setMediaType("MOVIE"); item1.setPlaylistId(10L);
        PlaylistItem item2 = new PlaylistItem(); item2.setPlexId("m2"); item2.setMediaType("MOVIE"); item2.setPlaylistId(10L);
        PlaylistItem item3 = new PlaylistItem(); item3.setPlexId("m3"); item3.setMediaType("MOVIE"); item3.setPlaylistId(10L);
        when(itemRepo.findByPlaylistIdOrderByOrdinalAsc(10L)).thenReturn(List.of(item1, item2, item3));

        // But Plex only returned 2 items (partial fetch — fewer than leafCount=5)
        when(plexClient.getPlaylistItems("pl1")).thenReturn(List.of(plexItem("m1", "movie"), plexItem("m2", "movie")));

        service.syncAll();

        // Guard must block ALL removals and cancels
        verify(itemRepo, never()).deleteByPlaylistIdAndPlexId(anyLong(), anyString());
        verify(downloadService, never()).doCancelItem(any());
        // Additions must also be skipped (don't trust partial fetch)
        verify(itemRepo, never()).save(any());
    }

    /**
     * Guard test: empty fetch while leafCount > 0 (worst-case data-loss scenario —
     * transient null/empty response) must NOT delete any items or files.
     */
    @Test
    void syncPlaylist_emptyFetchWithNonZeroLeafCount_skipsRemovalsAndCancels() {
        PlexPlaylist pp = plexPlaylistWithLeafCount("pl1", 3);
        when(plexClient.getPlaylists()).thenReturn(List.of(pp));

        Playlist local = localPlaylist(10L, "pl1");
        when(playlistRepo.findByPlexId("pl1")).thenReturn(Optional.of(local));
        when(playlistRepo.save(any())).thenReturn(local);

        PlaylistItem item1 = new PlaylistItem(); item1.setPlexId("m1"); item1.setMediaType("MOVIE"); item1.setPlaylistId(10L);
        when(itemRepo.findByPlaylistIdOrderByOrdinalAsc(10L)).thenReturn(List.of(item1));

        // Simulate transient empty response (null/empty) — leafCount=3 but 0 fetched
        when(plexClient.getPlaylistItems("pl1")).thenReturn(List.of());

        service.syncAll();

        verify(itemRepo, never()).deleteByPlaylistIdAndPlexId(anyLong(), anyString());
        verify(downloadService, never()).doCancelItem(any());
        verify(itemRepo, never()).save(any());
    }

    /**
     * Genuine removal test: when the full fetch matches leafCount and an item is truly
     * absent, it MUST be removed and its download cancelled.
     */
    @Test
    void syncPlaylist_genuineRemoval_removesItemAndCancels() {
        // leafCount=1 (Plex still has one item), another item was truly removed
        PlexPlaylist pp = plexPlaylistWithLeafCount("pl1", 1);
        when(plexClient.getPlaylists()).thenReturn(List.of(pp));

        Playlist local = localPlaylist(10L, "pl1");
        when(playlistRepo.findByPlexId("pl1")).thenReturn(Optional.of(local));
        when(playlistRepo.save(any())).thenReturn(local);

        // Locally we had 2 items
        PlaylistItem keep = new PlaylistItem(); keep.setPlexId("m1"); keep.setMediaType("MOVIE"); keep.setPlaylistId(10L);
        PlaylistItem gone = new PlaylistItem(); gone.setPlexId("m2"); gone.setMediaType("MOVIE"); gone.setPlaylistId(10L);
        when(itemRepo.findByPlaylistIdOrderByOrdinalAsc(10L)).thenReturn(List.of(keep, gone));

        // Plex now has 1 item — m2 is truly gone; fetched.size()==1 == leafCount==1 → guard passes
        when(plexClient.getPlaylistItems("pl1")).thenReturn(List.of(plexItem("m1", "movie")));

        User user = new User(); user.setId(1L);
        PlaylistSubscription sub = new PlaylistSubscription(); sub.setUser(user);
        when(subRepo.findByPlaylistIdWithUser(10L)).thenReturn(List.of(sub));

        Movie m2 = new Movie(); m2.setId(200L);
        when(movieRepo.findByPlexId("m2")).thenReturn(Optional.of(m2));
        DownloadQueueItem qi = new DownloadQueueItem(); qi.setId(99L);
        when(queueRepo.findByUser_IdAndMediaTypeAndMediaId(1L, DownloadQueueItem.MediaType.MOVIE, 200L))
            .thenReturn(Optional.of(qi));

        service.syncAll();

        // m2 must be removed
        verify(itemRepo).deleteByPlaylistIdAndPlexId(10L, "m2");
        verify(downloadService).doCancelItem(qi);
        // m1 must NOT be removed
        verify(itemRepo, never()).deleteByPlaylistIdAndPlexId(10L, "m1");
    }
}
