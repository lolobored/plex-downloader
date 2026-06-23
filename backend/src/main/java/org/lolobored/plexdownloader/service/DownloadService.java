package org.lolobored.plexdownloader.service;

import org.lolobored.plexdownloader.dto.DownloadQueueItemResponse;
import org.lolobored.plexdownloader.model.*;
import org.lolobored.plexdownloader.repository.*;
import org.lolobored.plexdownloader.transcode.TranscodeRequestedEvent;
import org.lolobored.plexdownloader.transcode.TranscodeService;
import org.lolobored.plexdownloader.util.SubtitleLangs;
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
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
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

    private boolean outputConfigured() {
        String moviesDir = settings.get("output.movies.dir").orElse("");
        String tvshowsDir = settings.get("output.tvshows.dir").orElse("");
        return !moviesDir.isBlank() && !tvshowsDir.isBlank();
    }

    public List<Long> enqueueMovie(Long movieId, User user, Long playlistId, Long qualityProfileId) {
        if (!outputConfigured()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Output folders not configured");
        }
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
        if (!outputConfigured()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Output folders not configured");
        }
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
        if (!outputConfigured()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Output folders not configured");
        }
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
        if (!outputConfigured()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Output folders not configured");
        }
        List<Season> seasons = seasonRepo.findByShowIdOrderBySeasonNumber(showId);
        if (seasons.isEmpty()) throw new IllegalArgumentException("Show not found or empty: " + showId);
        List<Long> ids = new ArrayList<>();
        for (Season season : seasons) {
            ids.addAll(enqueueSeason(season.getId(), user, qualityProfileId));
        }
        return ids;
    }

    private record EpisodeMeta(Long showId, Long seasonId, String showTitle, Integer seasonNumber) {}

    /** Zero-arg overload for callers that don't apply subtitle filters (unchanged behaviour). */
    public List<DownloadQueueItemResponse> getQueue(Long userId) {
        return getQueue(userId, null, null, null, null, null, null);
    }

    /**
     * Returns the queue for a user with optional subtitle filters applied Java-side
     * (queue is per-user and small, so in-memory filtering is acceptable).
     *
     * @param sourceSubtitles  "none" → source item has no subtitles; null → unfiltered
     * @param outputSubtitles  "none" → output has no subtitles; null → unfiltered
     * @param hasLang          language code — keep items whose source subtitle_langs contains this lang
     * @param missingLang      language code — keep items whose source subtitle_langs is scanned but lacks this lang
     * @param outputHasLang    language code — keep items whose output subtitle_langs contains this lang
     * @param outputMissingLang language code — keep items whose output subtitle_langs is scanned but lacks this lang
     */
    public List<DownloadQueueItemResponse> getQueue(Long userId,
                                                    String sourceSubtitles,
                                                    String outputSubtitles,
                                                    String hasLang,
                                                    String missingLang,
                                                    String outputHasLang,
                                                    String outputMissingLang) {
        List<DownloadQueueItem> items = queueRepo.findAllByUserIdWithProfileOrderByQueuePositionAsc(userId);

        // Batch-fetch episode → season → show metadata
        Set<Long> episodeIds = items.stream()
            .filter(i -> i.getMediaType() == DownloadQueueItem.MediaType.EPISODE)
            .map(DownloadQueueItem::getMediaId)
            .collect(Collectors.toSet());
        Map<Long, EpisodeMeta> episodeMeta = new HashMap<>();
        // Also collect episode subtitleLangs for source-side filtering
        Map<Long, String> episodeSubtitleLangs = new HashMap<>();
        if (!episodeIds.isEmpty()) {
            episodeRepo.findWithSeasonAndShowByIdIn(episodeIds).forEach(ep -> {
                episodeMeta.put(ep.getId(), new EpisodeMeta(
                    ep.getSeason().getShow().getId(),
                    ep.getSeason().getId(),
                    ep.getSeason().getShow().getTitle(),
                    ep.getSeason().getSeasonNumber()
                ));
                episodeSubtitleLangs.put(ep.getId(), ep.getSubtitleLangs());
            });
        }

        // Batch-fetch movie subtitleLangs for source-side filtering
        Set<Long> movieIds = items.stream()
            .filter(i -> i.getMediaType() == DownloadQueueItem.MediaType.MOVIE)
            .map(DownloadQueueItem::getMediaId)
            .collect(Collectors.toSet());
        Map<Long, String> movieSubtitleLangs = new HashMap<>();
        if (!movieIds.isEmpty()) {
            movieRepo.findAllById(movieIds)
                .forEach(m -> movieSubtitleLangs.put(m.getId(), m.getSubtitleLangs()));
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

        // Build subtitle filter predicates
        boolean srcNone = "none".equalsIgnoreCase(sourceSubtitles);
        boolean outNone = "none".equalsIgnoreCase(outputSubtitles);
        String hasToken          = hasLang          != null ? SubtitleLangs.token(hasLang)          : null;
        String missingToken      = missingLang      != null ? SubtitleLangs.token(missingLang)      : null;
        String outHasToken       = outputHasLang    != null ? SubtitleLangs.token(outputHasLang)    : null;
        String outMissingToken   = outputMissingLang != null ? SubtitleLangs.token(outputMissingLang) : null;
        boolean applyFilter = srcNone || outNone || hasToken != null || missingToken != null
                || outHasToken != null || outMissingToken != null;

        return items.stream()
            .filter(item -> {
                if (!applyFilter) return true;
                // Source subtitle_langs for this item
                String srcLangs = item.getMediaType() == DownloadQueueItem.MediaType.EPISODE
                    ? episodeSubtitleLangs.get(item.getMediaId())
                    : movieSubtitleLangs.get(item.getMediaId());
                String outLangs = item.getOutputSubtitleLangs();
                // Apply source-side subtitle filters
                if (srcNone   && !",".equals(srcLangs))                                      return false;
                if (hasToken  != null && (srcLangs == null || !srcLangs.contains(hasToken))) return false;
                if (missingToken != null && (srcLangs == null || srcLangs.contains(missingToken))) return false;
                // Apply output-side subtitle filters
                if (outNone          && !",".equals(outLangs))                                         return false;
                if (outHasToken      != null && (outLangs == null || !outLangs.contains(outHasToken))) return false;
                if (outMissingToken  != null && (outLangs == null || outLangs.contains(outMissingToken))) return false;
                return true;
            })
            .map(item -> {
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
        String srcName = Path.of(plexFilePath).getFileName().toString();
        String stem = srcName.replaceFirst("\\.[^.]+$", "");
        String outName = stem + profile.getContainer().extension();

        // subDir starts with "movies/" or "tvshows/" — pick configured root, strip the type prefix
        String destPath;
        if (subDir.startsWith("movies/")) {
            String root = settings.get("output.movies.dir")
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Output folders not configured"));
            String relative = subDir.substring("movies/".length());
            destPath = Path.of(root, relative, outName).toString();
        } else if (subDir.startsWith("tvshows/")) {
            String root = settings.get("output.tvshows.dir")
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Output folders not configured"));
            String relative = subDir.substring("tvshows/".length());
            destPath = Path.of(root, relative, outName).toString();
        } else {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Output folders not configured");
        }

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
            pruneEmptyParents(item);
        }
        queueRepo.delete(item);
    }

    /**
     * Removes now-empty parent folders of a deleted output file, walking up from the file's
     * directory. Deletes only directories strictly UNDER the media-type's configured output
     * root — never the root itself, nor anything outside it. Stops at the first non-empty dir.
     */
    private void pruneEmptyParents(DownloadQueueItem item) {
        String rootKey = item.getMediaType() == DownloadQueueItem.MediaType.MOVIE
            ? "output.movies.dir" : "output.tvshows.dir";
        String rootStr = settings.get(rootKey).filter(s -> !s.isBlank()).orElse(null);
        if (rootStr == null) return;
        Path root = Path.of(rootStr).normalize();
        Path dir = Path.of(item.getDestFilePath()).normalize().getParent();
        while (dir != null && dir.startsWith(root) && !dir.equals(root)) {
            try {
                if (!Files.isDirectory(dir)) break;
                try (var entries = Files.list(dir)) {
                    if (entries.findFirst().isPresent()) break; // non-empty — stop
                }
                Files.delete(dir);
                log.info("Pruned empty dir {}", dir);
            } catch (IOException e) {
                log.warn("Could not prune dir {}: {}", dir, e.getMessage());
                break;
            }
            dir = dir.getParent();
        }
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
        resetToQueued(item);
        queueRepo.save(item);
        events.publishEvent(new TranscodeRequestedEvent(id));
        return item;
    }

    @Transactional
    public int retryAllErrored(User user) {
        List<DownloadQueueItem> erroredItems =
            queueRepo.findByUser_IdAndStatus(user.getId(), DownloadQueueItem.Status.ERROR);
        for (DownloadQueueItem item : erroredItems) {
            resetToQueued(item);
            queueRepo.save(item);
            events.publishEvent(new TranscodeRequestedEvent(item.getId()));
        }
        return erroredItems.size();
    }

    @Transactional
    public DownloadQueueItem transcodeAgain(Long id, User user) {
        DownloadQueueItem item = queueRepo.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Queue item not found"));
        if (item.getUser() == null || !item.getUser().getId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your queue item");
        }
        if (item.getStatus() != DownloadQueueItem.Status.DONE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item is not DONE");
        }
        resetToQueued(item);
        queueRepo.save(item);
        events.publishEvent(new TranscodeRequestedEvent(id));
        return item;
    }

    private void resetToQueued(DownloadQueueItem item) {
        item.setStatus(DownloadQueueItem.Status.QUEUED);
        item.setErrorMessage(null);
        item.setTranscodeError(null);
        item.setProgressPercent(null);
        item.setCompletedAt(null);
        item.setCompressionRatio(null);
        item.setSourceSizeBytes(null);
        item.setOutputSizeBytes(null);
    }

}
