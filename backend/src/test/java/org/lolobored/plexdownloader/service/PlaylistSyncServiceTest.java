// backend/src/test/java/org/lolobored/plexdownloader/service/PlaylistSyncServiceTest.java
package org.lolobored.plexdownloader.service;

import org.lolobored.plexdownloader.client.PlexMediaServerClient;
import org.lolobored.plexdownloader.client.TdarrClient;
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
    @Mock TdarrClient tdarrClient;
    @InjectMocks PlaylistSyncService service;

    private PlexPlaylist plexPlaylist(String key) {
        PlexPlaylist p = new PlexPlaylist();
        p.setRatingKey(key); p.setTitle("Test"); p.setPlaylistType("video");
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
        when(itemRepo.findByPlaylistIdOrderByOrdinalAsc(10L)).thenReturn(List.of()); // nothing old
        when(plexClient.getPlaylistItems("pl1")).thenReturn(List.of(plexItem("m1", "movie")));

        User user = new User(); user.setId(1L);
        PlaylistSubscription sub = new PlaylistSubscription(); sub.setUser(user);
        when(subRepo.findByPlaylistIdWithUser(10L)).thenReturn(List.of(sub));

        Movie m = new Movie(); m.setId(100L);
        when(movieRepo.findByPlexId("m1")).thenReturn(Optional.of(m));
        when(queueRepo.existsByUser_IdAndMediaTypeAndMediaId(1L, DownloadQueueItem.MediaType.MOVIE, 100L)).thenReturn(false);
        when(downloadService.enqueueMovie(100L, user)).thenReturn(List.of(1L));

        service.syncAll();

        verify(downloadService).enqueueMovie(100L, user);
        verify(itemRepo).save(argThat(i -> "m1".equals(i.getPlexId()) && "MOVIE".equals(i.getMediaType())));
    }

    @Test
    void syncAll_cancelsRemovedItem_deletesFileAndTdarr(@TempDir Path tempDir) throws Exception {
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

        verify(queueRepo).delete(qi);
        verify(tdarrClient).deleteFile(destFile.toString());
        assertThat(destFile).doesNotExist();
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

        service.syncAll();

        verify(downloadService, never()).enqueueMovie(anyLong(), any());
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

        service.syncAll();

        verify(downloadService, never()).enqueueMovie(anyLong(), any());
    }

    @Test
    void syncAll_doesNotThrow_whenPlexUnreachable() {
        when(plexClient.getPlaylists()).thenThrow(new RuntimeException("Plex unreachable"));
        service.syncAll();  // must not throw
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
        when(downloadService.enqueueMovie(100L, user)).thenReturn(List.of(1L));
        when(downloadService.enqueueEpisode(200L, user)).thenReturn(List.of(2L));

        service.enqueueForSubscription(10L, user);

        verify(downloadService).enqueueMovie(100L, user);
        verify(downloadService).enqueueEpisode(200L, user);
    }
}
