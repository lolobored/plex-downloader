package org.lolobored.plexdownloader.service;

import org.lolobored.plexdownloader.dto.DownloadQueueItemResponse;
import org.lolobored.plexdownloader.model.*;
import org.lolobored.plexdownloader.repository.*;
import org.lolobored.plexdownloader.transcode.TranscodeRequestedEvent;
import org.lolobored.plexdownloader.transcode.TranscodeService;
import java.util.HashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DownloadService {

    private final MovieRepository movieRepo;
    private final EpisodeRepository episodeRepo;
    private final SeasonRepository seasonRepo;
    private final TvShowRepository showRepo;
    private final DownloadQueueRepository queueRepo;
    private final SettingsService settings;
    private final QualityProfileService qualityProfileService;
    private final ApplicationEventPublisher events;
    private final PlaylistRepository playlistRepo;
    private final TranscodeService transcodeService;

    public List<Long> enqueueMovie(Long movieId, User user) {
        return enqueueMovie(movieId, user, null, null);
    }

    public List<Long> enqueueMovie(Long movieId, User user, Long playlistId) {
        return enqueueMovie(movieId, user, playlistId, null);
    }

    public List<Long> enqueueMovie(Long movieId, User user, Long playlistId, Long qualityProfileId) {
        Movie movie = movieRepo.findById(movieId)
            .orElseThrow(() -> new IllegalArgumentException("Movie not found: " + movieId));
        QualityProfile profile = qualityProfileService.resolveOrDefault(qualityProfileId);
        String subDir = "movies/" + Path.of(movie.getFilePath()).getParent().getFileName().toString();
        DownloadQueueItem item = buildItem(user, DownloadQueueItem.MediaType.MOVIE,
            movieId, movie.getFilePath(), subDir, movie.getTitle(), profile);
        item.setPlaylistId(playlistId);
        item = queueRepo.save(item);
        events.publishEvent(new TranscodeRequestedEvent(item.getId()));
        return List.of(item.getId());
    }

    public List<Long> enqueueEpisode(Long episodeId, User user) {
        return enqueueEpisode(episodeId, user, null, null);
    }

    public List<Long> enqueueEpisode(Long episodeId, User user, Long playlistId) {
        return enqueueEpisode(episodeId, user, playlistId, null);
    }

    public List<Long> enqueueEpisode(Long episodeId, User user, Long playlistId, Long qualityProfileId) {
        Episode ep = episodeRepo.findById(episodeId)
            .orElseThrow(() -> new IllegalArgumentException("Episode not found: " + episodeId));
        Season season = seasonRepo.findById(ep.getSeason().getId())
            .orElseThrow(() -> new IllegalArgumentException("Season not found for episode: " + episodeId));
        TvShow show = showRepo.findById(season.getShow().getId())
            .orElseThrow(() -> new IllegalArgumentException("Show not found for episode: " + episodeId));
        String subDir = "tvshows/" + Path.of(ep.getFilePath()).getParent().getParent().getFileName().toString()
                        + "/" + Path.of(ep.getFilePath()).getParent().getFileName().toString();
        String epTitle = show.getTitle() + " S" + String.format("%02d", season.getSeasonNumber())
                         + "E" + String.format("%02d", ep.getEpisodeNumber())
                         + (ep.getTitle() != null ? " - " + ep.getTitle() : "");
        QualityProfile profile = qualityProfileService.resolveOrDefault(qualityProfileId);
        DownloadQueueItem item = buildItem(user, DownloadQueueItem.MediaType.EPISODE,
            episodeId, ep.getFilePath(), subDir, epTitle, profile);
        item.setPlaylistId(playlistId);
        item = queueRepo.save(item);
        events.publishEvent(new TranscodeRequestedEvent(item.getId()));
        return List.of(item.getId());
    }

    public List<Long> enqueueSeason(Long seasonId, User user) {
        return enqueueSeason(seasonId, user, null);
    }

    public List<Long> enqueueSeason(Long seasonId, User user, Long qualityProfileId) {
        Season season = seasonRepo.findById(seasonId)
            .orElseThrow(() -> new IllegalArgumentException("Season not found: " + seasonId));
        TvShow show = showRepo.findById(season.getShow().getId())
            .orElseThrow(() -> new IllegalArgumentException("Show not found for season: " + seasonId));
        List<Episode> episodes = episodeRepo.findBySeasonIdOrderByEpisodeNumber(seasonId);
        if (episodes.isEmpty()) throw new IllegalArgumentException("Season has no episodes: " + seasonId);
        QualityProfile profile = qualityProfileService.resolveOrDefault(qualityProfileId);
        List<Long> ids = new ArrayList<>();
        for (Episode ep : episodes) {
            String subDir = "tvshows/" + Path.of(ep.getFilePath()).getParent().getParent().getFileName().toString()
                            + "/" + Path.of(ep.getFilePath()).getParent().getFileName().toString();
            String epTitle = show.getTitle() + " S" + String.format("%02d", season.getSeasonNumber())
                             + "E" + String.format("%02d", ep.getEpisodeNumber())
                             + (ep.getTitle() != null ? " - " + ep.getTitle() : "");
            DownloadQueueItem item = buildItem(user, DownloadQueueItem.MediaType.EPISODE,
                ep.getId(), ep.getFilePath(), subDir, epTitle, profile);
            item = queueRepo.save(item);
            events.publishEvent(new TranscodeRequestedEvent(item.getId()));
            ids.add(item.getId());
        }
        return ids;
    }

    public List<Long> enqueueShow(Long showId, User user) {
        return enqueueShow(showId, user, null);
    }

    public List<Long> enqueueShow(Long showId, User user, Long qualityProfileId) {
        List<Season> seasons = seasonRepo.findByShowIdOrderBySeasonNumber(showId);
        if (seasons.isEmpty()) throw new IllegalArgumentException("Show not found or empty: " + showId);
        List<Long> ids = new ArrayList<>();
        for (Season season : seasons) {
            ids.addAll(enqueueSeason(season.getId(), user, qualityProfileId));
        }
        return ids;
    }

    private record EpisodeMeta(Long showId, Long seasonId, String showTitle, Integer seasonNumber) {}

    public List<DownloadQueueItemResponse> getQueue(Long userId) {
        List<DownloadQueueItem> items = queueRepo.findAllByUserIdOrderByQueuePositionAsc(userId);

        // Batch-fetch episode → season → show metadata
        Set<Long> episodeIds = items.stream()
            .filter(i -> i.getMediaType() == DownloadQueueItem.MediaType.EPISODE)
            .map(DownloadQueueItem::getMediaId)
            .collect(Collectors.toSet());
        Map<Long, EpisodeMeta> episodeMeta = new HashMap<>();
        if (!episodeIds.isEmpty()) {
            episodeRepo.findWithSeasonAndShowByIdIn(episodeIds).forEach(ep ->
                episodeMeta.put(ep.getId(), new EpisodeMeta(
                    ep.getSeason().getShow().getId(),
                    ep.getSeason().getId(),
                    ep.getSeason().getShow().getTitle(),
                    ep.getSeason().getSeasonNumber()
                ))
            );
        }

        // Batch-fetch playlist titles
        Set<Long> playlistIds = items.stream()
            .filter(i -> i.getPlaylistId() != null)
            .map(DownloadQueueItem::getPlaylistId)
            .collect(Collectors.toSet());
        Map<Long, String> playlistTitles = new HashMap<>();
        if (!playlistIds.isEmpty()) {
            playlistRepo.findAllById(playlistIds)
                .forEach(p -> playlistTitles.put(p.getId(), p.getTitle()));
        }

        return items.stream().map(item -> {
            Long playlistId    = item.getPlaylistId();
            String playlistTitle = playlistId != null ? playlistTitles.get(playlistId) : null;
            if (item.getMediaType() == DownloadQueueItem.MediaType.EPISODE) {
                EpisodeMeta em = episodeMeta.get(item.getMediaId());
                return DownloadQueueItemResponse.from(item,
                    em != null ? em.showId()       : null,
                    em != null ? em.seasonId()     : null,
                    playlistId, playlistTitle,
                    em != null ? em.showTitle()    : null,
                    em != null ? em.seasonNumber() : null
                );
            }
            return DownloadQueueItemResponse.from(item, null, null, playlistId, playlistTitle, null, null);
        }).toList();
    }

    static String slugify(String title) {
        if (title == null) return "unknown";
        return title.toLowerCase()
                    .replaceAll("[^a-z0-9\\s]", "")
                    .trim()
                    .replaceAll("\\s+", "_");
    }

    private DownloadQueueItem buildItem(User user, DownloadQueueItem.MediaType type,
                                        Long mediaId, String plexFilePath, String subDir,
                                        String title, QualityProfile profile) {
        String conversionDir = settings.get("plex.conversion.dir").orElse("/plex-conversion");
        String srcName = Path.of(plexFilePath).getFileName().toString();
        String stem = srcName.replaceFirst("\\.[^.]+$", "");
        String outName = stem + profile.getContainer().extension();
        String destPath = Path.of(conversionDir, "libraries", subDir, outName).toString();

        int nextPos = queueRepo.findMaxQueuePosition().orElse(0) + 1;

        DownloadQueueItem item = new DownloadQueueItem();
        item.setUser(user);
        item.setMediaType(type);
        item.setMediaId(mediaId);
        item.setTitle(title);
        item.setSourceFilePath(plexFilePath);
        item.setDestFilePath(destPath);
        item.setQualityProfile(profile);
        item.setQueuePosition(nextPos);
        item.setStatus(DownloadQueueItem.Status.QUEUED);
        return item;
    }

    @Transactional
    public void cancel(Long itemId, User user) {
        DownloadQueueItem item = queueRepo.findById(itemId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Queue item not found"));

        if (!item.getUser().getId().equals(user.getId()) && user.getRole() != User.Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your queue item");
        }
        if (item.getStatus() == DownloadQueueItem.Status.TRANSCODING) {
            transcodeService.cancel(itemId);
        }
        doCancelItem(item);
    }

    @Transactional
    public int cancelAllForShow(Long userId, Long showId) {
        List<DownloadQueueItem> items = queueRepo.findAllByUserIdAndShowId(userId, showId);
        int cancelled = 0;
        for (DownloadQueueItem item : items) {
            if (item.getStatus() == DownloadQueueItem.Status.TRANSCODING) {
                transcodeService.cancel(item.getId());
            }
            doCancelItem(item);
            cancelled++;
        }
        return cancelled;
    }

    @Transactional
    public int cancelAllForSeason(Long userId, Long seasonId) {
        List<DownloadQueueItem> items = queueRepo.findAllByUserIdAndSeasonId(userId, seasonId);
        int cancelled = 0;
        for (DownloadQueueItem item : items) {
            if (item.getStatus() == DownloadQueueItem.Status.TRANSCODING) {
                transcodeService.cancel(item.getId());
            }
            doCancelItem(item);
            cancelled++;
        }
        return cancelled;
    }

    // Package-private for testing
    void doCancelItem(DownloadQueueItem item) {
        if (item.getDestFilePath() != null) {
            try {
                Files.deleteIfExists(Path.of(item.getDestFilePath()));
            } catch (IOException e) {
                log.warn("Could not delete output file {}: {}", item.getDestFilePath(), e.getMessage());
            }
        }
        queueRepo.delete(item);
    }

    @Transactional
    public DownloadQueueItem retry(Long id, User user) {
        DownloadQueueItem item = queueRepo.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Queue item not found"));
        if (item.getUser() == null || !item.getUser().getId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your queue item");
        }
        if (item.getStatus() != DownloadQueueItem.Status.ERROR) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item is not in ERROR state");
        }
        item.setStatus(DownloadQueueItem.Status.QUEUED);
        item.setErrorMessage(null);
        item.setTranscodeError(null);
        item.setProgressPercent(null);
        queueRepo.save(item);
        events.publishEvent(new TranscodeRequestedEvent(id));
        return item;
    }

}
