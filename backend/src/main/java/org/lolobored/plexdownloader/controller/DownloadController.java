package org.lolobored.plexdownloader.controller;

import org.lolobored.plexdownloader.dto.DownloadQueueItemResponse;
import org.lolobored.plexdownloader.dto.DownloadRequest;
import org.lolobored.plexdownloader.dto.DownloadResponse;
import org.lolobored.plexdownloader.model.DownloadQueueItem;
import org.lolobored.plexdownloader.model.User;
import org.lolobored.plexdownloader.service.DownloadService;
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

    @PostMapping
    public DownloadResponse download(@RequestBody DownloadRequest req,
                                     @AuthenticationPrincipal User user) {
        List<Long> jobIds = switch (req.type()) {
            case "MOVIE"   -> downloadService.enqueueMovie(req.id(), user, null, req.qualityProfileId());
            case "EPISODE" -> downloadService.enqueueEpisode(req.id(), user, null, req.qualityProfileId());
            case "SEASON"  -> downloadService.enqueueSeason(req.id(), user, req.qualityProfileId());
            case "SHOW"    -> downloadService.enqueueShow(req.id(), user, req.qualityProfileId());
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown type: " + req.type());
        };
        return new DownloadResponse(jobIds, "QUEUED");
    }

    @GetMapping("/queue")
    public List<DownloadQueueItemResponse> getQueue(
            @AuthenticationPrincipal User user,
            @RequestParam(required = false) String sourceSubtitles,
            @RequestParam(required = false) String outputSubtitles,
            @RequestParam(required = false) String hasLang,
            @RequestParam(required = false) String missingLang) {
        return downloadService.getQueue(user.getId(), sourceSubtitles, outputSubtitles, hasLang, missingLang);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable Long id,
                       @AuthenticationPrincipal User user) {
        downloadService.cancel(id, user);
    }

    @PostMapping("/{id}/retry")
    public DownloadQueueItem retry(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return downloadService.retry(id, user);
    }

    @PostMapping("/retry-all-errored")
    public java.util.Map<String, Integer> retryAllErrored(@AuthenticationPrincipal User user) {
        int count = downloadService.retryAllErrored(user);
        return java.util.Map.of("retried", count);
    }

    @PostMapping("/{id}/transcode-again")
    public DownloadQueueItem transcodeAgain(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return downloadService.transcodeAgain(id, user);
    }
}
