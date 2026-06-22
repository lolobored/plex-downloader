package org.lolobored.plexdownloader.service;

import org.lolobored.plexdownloader.client.PlexMediaServerClient;
import org.lolobored.plexdownloader.client.dto.PlexItem;
import org.lolobored.plexdownloader.client.dto.PlexPlaylist;
import org.lolobored.plexdownloader.model.*;
import org.lolobored.plexdownloader.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final org.lolobored.plexdownloader.transcode.TranscodeService transcodeService;

    @Autowired
    @Lazy
    private PlaylistSyncService self;

    public void syncAll() {
        log.info("Playlist sync: fetching playlists from Plex");
        List<PlexPlaylist> plexPlaylists;
        try {
            plexPlaylists = plexClient.getPlaylists();
        } catch (Exception e) {
            log.warn("Failed to fetch playlists from Plex: {}", e.getMessage());
            return;
        }
        log.info("Playlist sync: found {} video playlist(s) on Plex", plexPlaylists.size());
        PlaylistSyncService proxy = self != null ? self : this;
        for (PlexPlaylist pp : plexPlaylists) {
            try {
                log.info("Playlist sync: syncing '{}' (ratingKey={})", pp.getTitle(), pp.getRatingKey());
                proxy.syncPlaylist(pp);
            } catch (Exception e) {
                log.warn("Failed to sync playlist {}: {}", pp.getRatingKey(), e.getMessage());
            }
        }
        // Clean up local playlists that no longer exist in Plex
        Set<String> activePlexIds = plexPlaylists.stream()
            .map(PlexPlaylist::getRatingKey)
            .collect(Collectors.toSet());
        playlistRepo.findAll().stream()
            .filter(local -> !activePlexIds.contains(local.getPlexId()))
            .forEach(orphan -> {
                log.info("Playlist sync: removing orphan playlist '{}' (no longer in Plex)", orphan.getTitle());
                itemRepo.deleteAllByPlaylistId(orphan.getId());
                subRepo.deleteByPlaylistId(orphan.getId());
                playlistRepo.delete(orphan);
            });
        log.info("Playlist sync: done");
    }

    @Transactional
    public void syncPlaylist(PlexPlaylist pp) {
        log.info("Playlist '{}': fetching {} item(s) from Plex", pp.getTitle(), pp.getLeafCount());
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

        // Safety guard: if Plex reported more items than we received, the fetch is partial/failed.
        // Never remove items (or cancel downloads) based on an incomplete fetch — that would cause
        // data loss by deleting files for items still in the playlist.
        int leafCount = pp.getLeafCount();
        if (leafCount > fetched.size()) {
            log.warn("Playlist '{}': partial fetch detected — Plex leafCount={} but only {} item(s) received. " +
                     "Skipping removals/additions to avoid data loss. Will retry on next sync.",
                pp.getTitle(), leafCount, fetched.size());
            return;
        }

        Map<String, PlexItem> fetchedByKey = fetched.stream()
            .collect(Collectors.toMap(PlexItem::getRatingKey, i -> i, (a, b) -> a));
        Set<String> newPlexIds = fetchedByKey.keySet();

        Set<String> added   = new HashSet<>(newPlexIds); added.removeAll(oldPlexIds);
        Set<String> removed = new HashSet<>(oldPlexIds); removed.removeAll(newPlexIds);
        log.info("Playlist '{}': {} item(s) fetched, +{} added, -{} removed",
            pp.getTitle(), fetched.size(), added.size(), removed.size());
        if (!removed.isEmpty()) {
            log.info("Playlist '{}': removing plexIds from playlist_items: {}", pp.getTitle(), removed);
        }
        if (log.isDebugEnabled()) {
            log.debug("Playlist '{}': oldPlexIds={}", pp.getTitle(), oldPlexIds);
            log.debug("Playlist '{}': newPlexIds from Plex={}", pp.getTitle(), newPlexIds);
        }

        // Persist removals
        for (String plexId : removed) {
            log.info("Playlist '{}': deleting playlist_item plexId={} playlistId={}", pp.getTitle(), plexId, local.getId());
            itemRepo.deleteByPlaylistIdAndPlexId(local.getId(), plexId);
        }

        // Persist additions
        int ordinalBase = oldItems.size();
        int ordinalOffset = 0;
        for (String plexId : added) {
            PlexItem pi = fetchedByKey.get(plexId);
            if (pi == null) continue;
            String mediaType = mapMediaType(pi.getType());
            if (mediaType == null) {
                log.debug("Skipping playlist item {} with unknown type {}", pi.getRatingKey(), pi.getType());
                continue;
            }
            PlaylistItem item = new PlaylistItem();
            item.setPlaylistId(local.getId());
            item.setPlexId(pi.getRatingKey());
            item.setMediaType(mediaType);
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
                if (pi != null) {
                    String mt = mapMediaType(pi.getType());
                    if (mt != null) enqueueItem(user, pi.getRatingKey(), mt, local.getId());
                }
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
            enqueueItem(user, pi.getPlexId(), pi.getMediaType(), playlistId);
        }
    }

    // Package-private for testing
    void enqueueItem(User user, String plexId, String mediaType, Long playlistId) {
        if ("MOVIE".equals(mediaType)) {
            movieRepo.findByPlexId(plexId).ifPresent(m -> {
                if (!queueRepo.existsByUser_IdAndMediaTypeAndMediaId(
                        user.getId(), DownloadQueueItem.MediaType.MOVIE, m.getId())) {
                    try { downloadService.enqueueMovie(m.getId(), user, playlistId); }
                    catch (Exception e) { log.warn("Failed to enqueue movie {}: {}", m.getId(), e.getMessage()); }
                }
            });
        } else if ("EPISODE".equals(mediaType)) {
            episodeRepo.findByPlexId(plexId).ifPresent(ep -> {
                if (!queueRepo.existsByUser_IdAndMediaTypeAndMediaId(
                        user.getId(), DownloadQueueItem.MediaType.EPISODE, ep.getId())) {
                    try { downloadService.enqueueEpisode(ep.getId(), user, playlistId); }
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
        // doCancelItem deletes the output file, prunes now-empty parent dirs, and removes the row.
        queueRepo.findByUser_IdAndMediaTypeAndMediaId(user.getId(), type, mediaId)
            .ifPresent(downloadService::doCancelItem);
    }

    public int countQueuedForUser(Long userId, Long playlistId) {
        return queueRepo.findAllByUserIdAndPlaylistId(userId, playlistId).size();
    }

    @Transactional
    public int cancelAllForUser(Long userId, Long playlistId) {
        List<DownloadQueueItem> items = queueRepo.findAllByUserIdAndPlaylistId(userId, playlistId);
        int cancelled = 0;
        for (DownloadQueueItem item : items) {
            if (item.getStatus() == DownloadQueueItem.Status.TRANSCODING) {
                transcodeService.cancel(item.getId());
            }
            downloadService.doCancelItem(item);
            cancelled++;
        }
        return cancelled;
    }

    private Long resolveLocalId(String plexId, String mediaType) {
        if ("MOVIE".equals(mediaType)) return movieRepo.findByPlexId(plexId).map(Movie::getId).orElse(null);
        if ("EPISODE".equals(mediaType)) return episodeRepo.findByPlexId(plexId).map(Episode::getId).orElse(null);
        return null;
    }

    private String mapMediaType(String plexType) {
        if ("movie".equals(plexType)) return "MOVIE";
        if ("episode".equals(plexType)) return "EPISODE";
        return null;
    }
}
