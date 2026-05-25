package org.lolobored.plexdownloader.controller;

import org.lolobored.plexdownloader.dto.*;
import org.lolobored.plexdownloader.model.*;
import org.lolobored.plexdownloader.repository.*;
import org.lolobored.plexdownloader.service.PlaylistSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/playlists")
@RequiredArgsConstructor
public class PlaylistController {

    private final PlaylistRepository playlistRepo;
    private final PlaylistItemRepository itemRepo;
    private final PlaylistSubscriptionRepository subRepo;
    private final MovieRepository movieRepo;
    private final EpisodeRepository episodeRepo;
    private final DownloadQueueRepository queueRepo;
    private final PlaylistSyncService playlistSyncService;

    @GetMapping
    public List<PlaylistResponse> getPlaylists(@AuthenticationPrincipal User user) {
        return playlistRepo.findAll().stream()
            .map(p -> toResponse(p, user.getId()))
            .toList();
    }

    @GetMapping("/{id}")
    public PlaylistDetailResponse getPlaylist(@PathVariable Long id, @AuthenticationPrincipal User user) {
        Playlist playlist = playlistRepo.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        List<PlaylistItemResponse> items = itemRepo.findByPlaylistIdOrderByOrdinalAsc(id).stream()
            .map(pi -> toItemResponse(pi, user.getId()))
            .toList();
        List<String> posterPlexIds = itemRepo.findTop4ByPlaylistIdOrderByOrdinalAsc(id).stream()
            .map(PlaylistItem::getPlexId).toList();
        boolean subscribed = subRepo.existsByUserIdAndPlaylistId(user.getId(), id);
        return new PlaylistDetailResponse(playlist.getId(), playlist.getPlexId(), playlist.getTitle(),
            playlist.getPlaylistType(), playlist.getLeafCount(), subscribed, posterPlexIds, items);
    }

    @PostMapping("/{id}/subscribe")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void subscribe(@PathVariable Long id, @AuthenticationPrincipal User user) {
        Playlist playlist = playlistRepo.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!subRepo.existsByUserIdAndPlaylistId(user.getId(), id)) {
            PlaylistSubscription sub = new PlaylistSubscription();
            sub.setUser(user);
            sub.setPlaylist(playlist);
            subRepo.save(sub);
            playlistSyncService.enqueueForSubscription(id, user);
        }
    }

    @GetMapping("/{id}/queue-count")
    public Map<String, Integer> getQueueCount(@PathVariable Long id,
                                               @AuthenticationPrincipal User user) {
        return Map.of("count", playlistSyncService.countQueuedForUser(user.getId(), id));
    }

    @DeleteMapping("/{id}/subscribe")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsubscribe(@PathVariable Long id, @AuthenticationPrincipal User user) {
        playlistSyncService.cancelAllForUser(user.getId(), id);
        subRepo.deleteByUserIdAndPlaylistId(user.getId(), id);
    }

    private PlaylistResponse toResponse(Playlist p, Long userId) {
        List<String> posterPlexIds = itemRepo.findTop4ByPlaylistIdOrderByOrdinalAsc(p.getId()).stream()
            .map(PlaylistItem::getPlexId).toList();
        boolean subscribed = subRepo.existsByUserIdAndPlaylistId(userId, p.getId());
        return new PlaylistResponse(p.getId(), p.getPlexId(), p.getTitle(),
            p.getPlaylistType(), p.getLeafCount(), subscribed, posterPlexIds);
    }

    private PlaylistItemResponse toItemResponse(PlaylistItem pi, Long userId) {
        String title = null;
        Integer year = null;
        Long mediaId = null;

        if ("MOVIE".equals(pi.getMediaType())) {
            Optional<Movie> mOpt = movieRepo.findByPlexId(pi.getPlexId());
            if (mOpt.isPresent()) {
                Movie m = mOpt.get();
                title = m.getTitle();
                year = m.getYear();
                mediaId = m.getId();
            }
        } else if ("EPISODE".equals(pi.getMediaType())) {
            Optional<Episode> epOpt = episodeRepo.findByPlexId(pi.getPlexId());
            if (epOpt.isPresent()) {
                Episode ep = epOpt.get();
                title = ep.getTitle();
                year = ep.getAirDate() != null ? ep.getAirDate().getYear() : null;
                mediaId = ep.getId();
            }
        }

        String queueStatus = null;
        String tdarrStatus = null;
        if (mediaId != null) {
            DownloadQueueItem.MediaType type = DownloadQueueItem.MediaType.valueOf(pi.getMediaType());
            Optional<DownloadQueueItem> qiOpt =
                queueRepo.findByUser_IdAndMediaTypeAndMediaId(userId, type, mediaId);
            if (qiOpt.isPresent()) {
                queueStatus = qiOpt.get().getStatus().name();
                tdarrStatus = qiOpt.get().getTdarrStatus() != null ? qiOpt.get().getTdarrStatus().name() : null;
            }
        }

        return new PlaylistItemResponse(pi.getId(), pi.getPlexId(), pi.getMediaType(),
            pi.getOrdinal(), title, year, queueStatus, tdarrStatus);
    }
}
