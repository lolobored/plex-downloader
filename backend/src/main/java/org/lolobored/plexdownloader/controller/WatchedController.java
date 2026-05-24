package org.lolobored.plexdownloader.controller;

import org.lolobored.plexdownloader.dto.WatchedResponse;
import org.lolobored.plexdownloader.model.User;
import org.lolobored.plexdownloader.service.WatchedSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class WatchedController {

    private final WatchedSyncService watchedSyncService;

    @GetMapping("/api/tv/{showId}/watched")
    public WatchedResponse getWatched(@PathVariable Long showId,
                                       @AuthenticationPrincipal User user) {
        watchedSyncService.syncIfStale(user.getId(), showId);
        return new WatchedResponse(watchedSyncService.getWatchedEpisodeIds(user.getId(), showId));
    }
}
