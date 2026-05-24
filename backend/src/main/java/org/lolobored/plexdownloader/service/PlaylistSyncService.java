package org.lolobored.plexdownloader.service;

import org.lolobored.plexdownloader.client.PlexMediaServerClient;
import org.lolobored.plexdownloader.client.TdarrClient;
import org.lolobored.plexdownloader.client.dto.PlexItem;
import org.lolobored.plexdownloader.client.dto.PlexPlaylist;
import org.lolobored.plexdownloader.model.*;
import org.lolobored.plexdownloader.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlaylistSyncService {

    private final PlexMediaServerClient plexClient;
    private final PlaylistRepository playlistRepo;
    private final PlaylistItemRepository itemRepo;
    private final PlaylistSubscriptionRepository subRepo;
    private final MovieRepository movieRepo;
    private final EpisodeRepository episodeRepo;
    private final DownloadQueueRepository queueRepo;
    private final DownloadService downloadService;
    private final TdarrClient tdarrClient;

    public void syncAll() {
        List<PlexPlaylist> plexPlaylists;
        try {
            plexPlaylists = plexClient.getPlaylists();
        } catch (Exception e) {
            log.warn("Failed to fetch playlists from Plex: {}", e.getMessage());
            return;
        }
        for (PlexPlaylist pp : plexPlaylists) {
            try {
                syncPlaylist(pp);
            } catch (Exception e) {
                log.warn("Failed to sync playlist {}: {}", pp.getRatingKey(), e.getMessage());
            }
        }
    }

    private void syncPlaylist(PlexPlaylist pp) {
        // Upsert Playlist row
        Playlist local = playlistRepo.findByPlexId(pp.getRatingKey()).orElseGet(Playlist::new);
        local.setPlexId(pp.getRatingKey());
        local.setTitle(pp.getTitle());
        local.setPlaylistType(pp.getPlaylistType());
        local.setLeafCount(pp.getLeafCount());
        local.setSyncedAt(Instant.now());
        local = playlistRepo.save(local);

        // Snapshot old items before update
        List<PlaylistItem> oldItems = itemRepo.findByPlaylistIdOrderByOrdinalAsc(local.getId());
        Set<String> oldPlexIds = oldItems.stream().map(PlaylistItem::getPlexId).collect(Collectors.toSet());
        Map<String, PlaylistItem> oldByPlexId = oldItems.stream()
            .collect(Collectors.toMap(PlaylistItem::getPlexId, i -> i));

        // Fetch new state from Plex
        List<PlexItem> fetched = plexClient.getPlaylistItems(pp.getRatingKey());
        Map<String, PlexItem> fetchedByKey = fetched.stream()
            .collect(Collectors.toMap(PlexItem::getRatingKey, i -> i, (a, b) -> a));
        Set<String> newPlexIds = fetchedByKey.keySet();

        Set<String> added   = new HashSet<>(newPlexIds); added.removeAll(oldPlexIds);
        Set<String> removed = new HashSet<>(oldPlexIds); removed.removeAll(newPlexIds);

        // Persist removals
        for (String plexId : removed) {
            itemRepo.deleteByPlaylistIdAndPlexId(local.getId(), plexId);
        }

        // Persist additions
        int ordinalBase = oldItems.size();
        int ordinalOffset = 0;
        for (String plexId : added) {
            PlexItem pi = fetchedByKey.get(plexId);
            if (pi == null) continue;
            PlaylistItem item = new PlaylistItem();
            item.setPlaylistId(local.getId());
            item.setPlexId(pi.getRatingKey());
            item.setMediaType(mapMediaType(pi.getType()));
            item.setOrdinal(ordinalBase + ordinalOffset++);
            itemRepo.save(item);
        }

        // React to diffs for each subscriber
        List<PlaylistSubscription> subs = subRepo.findByPlaylistIdWithUser(local.getId());
        if (subs.isEmpty()) return;

        for (PlaylistSubscription sub : subs) {
            User user = sub.getUser();
            for (String plexId : added) {
                PlexItem pi = fetchedByKey.get(plexId);
                if (pi != null) enqueueItem(user, pi.getRatingKey(), mapMediaType(pi.getType()));
            }
            for (String plexId : removed) {
                PlaylistItem pi = oldByPlexId.get(plexId);
                if (pi != null) cancelItem(user, pi.getPlexId(), pi.getMediaType());
            }
        }
    }

    /** Called when user subscribes — queues all current items immediately. */
    public void enqueueForSubscription(Long playlistId, User user) {
        List<PlaylistItem> items = itemRepo.findByPlaylistIdOrderByOrdinalAsc(playlistId);
        for (PlaylistItem pi : items) {
            enqueueItem(user, pi.getPlexId(), pi.getMediaType());
        }
    }

    // Package-private for testing
    void enqueueItem(User user, String plexId, String mediaType) {
        if ("MOVIE".equals(mediaType)) {
            movieRepo.findByPlexId(plexId).ifPresent(m -> {
                if (!queueRepo.existsByUser_IdAndMediaTypeAndMediaId(
                        user.getId(), DownloadQueueItem.MediaType.MOVIE, m.getId())) {
                    try { downloadService.enqueueMovie(m.getId(), user); }
                    catch (Exception e) { log.warn("Failed to enqueue movie {}: {}", m.getId(), e.getMessage()); }
                }
            });
        } else if ("EPISODE".equals(mediaType)) {
            episodeRepo.findByPlexId(plexId).ifPresent(ep -> {
                if (!queueRepo.existsByUser_IdAndMediaTypeAndMediaId(
                        user.getId(), DownloadQueueItem.MediaType.EPISODE, ep.getId())) {
                    try { downloadService.enqueueEpisode(ep.getId(), user); }
                    catch (Exception e) { log.warn("Failed to enqueue episode {}: {}", ep.getId(), e.getMessage()); }
                }
            });
        }
    }

    // Package-private for testing
    void cancelItem(User user, String plexId, String mediaType) {
        Long mediaId = resolveLocalId(plexId, mediaType);
        if (mediaId == null) return;
        DownloadQueueItem.MediaType type = DownloadQueueItem.MediaType.valueOf(mediaType);
        queueRepo.findByUser_IdAndMediaTypeAndMediaId(user.getId(), type, mediaId).ifPresent(qi -> {
            String destPath = qi.getDestFilePath();
            queueRepo.delete(qi);
            if (destPath != null) {
                try { Files.deleteIfExists(Path.of(destPath)); }
                catch (IOException e) { log.warn("Could not delete file {}: {}", destPath, e.getMessage()); }
                try { tdarrClient.deleteFile(destPath); }
                catch (Exception e) { log.warn("Tdarr deleteFile failed for {}: {}", destPath, e.getMessage()); }
            }
        });
    }

    private Long resolveLocalId(String plexId, String mediaType) {
        if ("MOVIE".equals(mediaType)) return movieRepo.findByPlexId(plexId).map(Movie::getId).orElse(null);
        if ("EPISODE".equals(mediaType)) return episodeRepo.findByPlexId(plexId).map(Episode::getId).orElse(null);
        return null;
    }

    private String mapMediaType(String plexType) {
        return "movie".equals(plexType) ? "MOVIE" : "EPISODE";
    }
}
