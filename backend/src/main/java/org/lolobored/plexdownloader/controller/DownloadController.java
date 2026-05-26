package org.lolobored.plexdownloader.controller;

import org.lolobored.plexdownloader.dto.DownloadQueueItemResponse;
import org.lolobored.plexdownloader.dto.DownloadRequest;
import org.lolobored.plexdownloader.dto.DownloadResponse;
import org.lolobored.plexdownloader.model.DownloadQueueItem;
import org.lolobored.plexdownloader.model.User;
import org.lolobored.plexdownloader.repository.DownloadQueueRepository;
import org.lolobored.plexdownloader.service.DownloadService;
import org.lolobored.plexdownloader.service.TdarrSyncScheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/download")
@RequiredArgsConstructor
public class DownloadController {

    private final DownloadService downloadService;
    private final TdarrSyncScheduler tdarrSync;
    private final DownloadQueueRepository queueRepo;

    @PostMapping
    public DownloadResponse download(@RequestBody DownloadRequest req,
                                     @AuthenticationPrincipal User user) {
        List<Long> jobIds = switch (req.type()) {
            case "MOVIE"   -> downloadService.enqueueMovie(req.id(), user);
            case "EPISODE" -> downloadService.enqueueEpisode(req.id(), user);
            case "SEASON"  -> downloadService.enqueueSeason(req.id(), user);
            case "SHOW"    -> downloadService.enqueueShow(req.id(), user);
            default -> throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Unknown type: " + req.type());
        };
        return new DownloadResponse(jobIds, "QUEUED");
    }

    @GetMapping("/queue")
    public List<DownloadQueueItemResponse> getQueue(@AuthenticationPrincipal User user) {
        return downloadService.getQueue(user.getId());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable Long id,
                       @AuthenticationPrincipal User user) {
        downloadService.cancel(id, user);
    }

    @PostMapping("/{id}/tdarr-refresh")
    public DownloadQueueItem refreshTdarrStatus(@PathVariable Long id) {
        return tdarrSync.syncOne(id);
    }

    @PostMapping("/{id}/retry")
    public DownloadQueueItem retryTdarr(@PathVariable Long id,
                                         @AuthenticationPrincipal User user) {
        DownloadQueueItem item = queueRepo.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Queue item not found"));
        User owner = item.getUser();
        if (owner == null || !owner.getId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your queue item");
        }
        return tdarrSync.requeueOne(id);
    }
}
