package com.plexdownloader.service;

import com.plexdownloader.model.*;
import com.plexdownloader.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DownloadService {

    private DownloadService self;
    private final MovieRepository movieRepo;
    private final EpisodeRepository episodeRepo;
    private final SeasonRepository seasonRepo;
    private final TvShowRepository showRepo;
    private final DownloadQueueRepository queueRepo;
    private final PathMappingService pathMapping;
    private final SettingsService settings;

    @Autowired
    @Lazy
    public void setSelf(DownloadService self) {
        this.self = self;
    }

    public List<Long> enqueueMovie(Long movieId, User user) {
        Movie movie = movieRepo.findById(movieId)
            .orElseThrow(() -> new IllegalArgumentException("Movie not found: " + movieId));
        DownloadQueueItem item = buildItem(user, DownloadQueueItem.MediaType.MOVIE,
            movieId, movie.getFilePath());
        item = queueRepo.save(item);
        self.executeCopyAsync(item.getId());
        return List.of(item.getId());
    }

    public List<Long> enqueueEpisode(Long episodeId, User user) {
        Episode ep = episodeRepo.findById(episodeId)
            .orElseThrow(() -> new IllegalArgumentException("Episode not found: " + episodeId));
        DownloadQueueItem item = buildItem(user, DownloadQueueItem.MediaType.EPISODE,
            episodeId, ep.getFilePath());
        item = queueRepo.save(item);
        self.executeCopyAsync(item.getId());
        return List.of(item.getId());
    }

    public List<Long> enqueueSeason(Long seasonId, User user) {
        List<Episode> episodes = episodeRepo.findBySeasonIdOrderByEpisodeNumber(seasonId);
        if (episodes.isEmpty()) throw new IllegalArgumentException("Season not found or empty: " + seasonId);
        List<Long> ids = new ArrayList<>();
        for (Episode ep : episodes) {
            DownloadQueueItem item = buildItem(user, DownloadQueueItem.MediaType.EPISODE,
                ep.getId(), ep.getFilePath());
            item = queueRepo.save(item);
            self.executeCopyAsync(item.getId());
            ids.add(item.getId());
        }
        return ids;
    }

    public List<Long> enqueueShow(Long showId, User user) {
        List<Season> seasons = seasonRepo.findByShowIdOrderBySeasonNumber(showId);
        if (seasons.isEmpty()) throw new IllegalArgumentException("Show not found or empty: " + showId);
        List<Long> ids = new ArrayList<>();
        for (Season season : seasons) {
            ids.addAll(enqueueSeason(season.getId(), user));
        }
        return ids;
    }

    public List<DownloadQueueItem> getQueue() {
        return queueRepo.findAllByOrderByQueuePositionAsc();
    }

    private DownloadQueueItem buildItem(User user, DownloadQueueItem.MediaType type,
                                        Long mediaId, String plexFilePath) {
        String appPath = pathMapping.translate(plexFilePath);
        String conversionDir = settings.getRequired("plex.conversion.dir");
        String filename = Path.of(appPath).getFileName().toString();
        String destPath = Path.of(conversionDir, filename).toString();

        int nextPos = queueRepo.findMaxQueuePosition().orElse(0) + 1;

        DownloadQueueItem item = new DownloadQueueItem();
        item.setUser(user);
        item.setMediaType(type);
        item.setMediaId(mediaId);
        item.setSourceFilePath(plexFilePath);
        item.setDestFilePath(destPath);
        item.setQueuePosition(nextPos);
        item.setStatus(DownloadQueueItem.Status.PENDING);
        return item;
    }

    @Async
    public void executeCopyAsync(Long itemId) {
        DownloadQueueItem item = queueRepo.findById(itemId).orElse(null);
        if (item == null) return;

        item.setStatus(DownloadQueueItem.Status.IN_PROGRESS);
        queueRepo.save(item);

        try {
            Path source = Path.of(pathMapping.translate(item.getSourceFilePath()));
            Path dest = Path.of(item.getDestFilePath());
            if (!Files.exists(source)) {
                throw new IOException("Source file not found: " + source);
            }
            Files.createDirectories(dest.getParent());
            if (!Files.exists(dest) || Files.size(dest) != Files.size(source)) {
                Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
            }
            item.setStatus(DownloadQueueItem.Status.DONE);
            item.setCompletedAt(Instant.now());
        } catch (IOException e) {
            item.setStatus(DownloadQueueItem.Status.ERROR);
            item.setErrorMessage(e.getMessage());
            log.error("Copy failed for item {}: {}", itemId, e.getMessage());
        }
        queueRepo.save(item);
    }
}
