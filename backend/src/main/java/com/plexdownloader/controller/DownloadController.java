package com.plexdownloader.controller;

import com.plexdownloader.dto.DownloadRequest;
import com.plexdownloader.dto.DownloadResponse;
import com.plexdownloader.model.DownloadQueueItem;
import com.plexdownloader.model.User;
import com.plexdownloader.service.DownloadService;
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
            case "MOVIE"   -> downloadService.enqueueMovie(req.id(), user);
            case "EPISODE" -> downloadService.enqueueEpisode(req.id(), user);
            case "SEASON"  -> downloadService.enqueueSeason(req.id(), user);
            case "SHOW"    -> downloadService.enqueueShow(req.id(), user);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown type: " + req.type());
        };
        return new DownloadResponse(jobIds, "QUEUED");
    }

    @GetMapping("/queue")
    public List<DownloadQueueItem> getQueue() {
        return downloadService.getQueue();
    }
}
