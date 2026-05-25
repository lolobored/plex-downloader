package org.lolobored.plexdownloader.controller;

import org.lolobored.plexdownloader.dto.*;
import org.lolobored.plexdownloader.model.User;
import org.lolobored.plexdownloader.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/subscriptions")
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionService subscriptionService;
    private final WatchedSyncService watchedSyncService;

    @GetMapping
    public List<SubscriptionResponse> getSubscriptions(@AuthenticationPrincipal User user) {
        return subscriptionService.listSubscriptions(user.getId());
    }

    @PostMapping
    public SubscriptionResponse subscribe(@RequestBody SubscriptionRequest req,
                                           @AuthenticationPrincipal User user) {
        if (req.targetCount() == null || !List.of(5, 10, 15, 20).contains(req.targetCount())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "targetCount must be 5, 10, 15, or 20");
        }
        SubscriptionResponse resp = subscriptionService.upsert(
            user.getId(), req.showId(), req.targetCount());
        watchedSyncService.syncShow(user.getId(), req.showId());
        subscriptionService.replenishIfSubscribed(user.getId(), req.showId());
        return resp;
    }

    @DeleteMapping("/{showId}")
    public ResponseEntity<Void> unsubscribe(@PathVariable Long showId,
                                             @AuthenticationPrincipal User user) {
        subscriptionService.cancel(user.getId(), showId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{showId}/queue-count")
    public Map<String, Integer> getQueueCount(@PathVariable Long showId,
                                               @AuthenticationPrincipal User user) {
        return Map.of("count", subscriptionService.getQueueCount(user.getId(), showId));
    }

    @PostMapping("/{showId}/sync")
    public WatchedResponse syncNow(@PathVariable Long showId,
                                    @AuthenticationPrincipal User user) {
        watchedSyncService.syncShow(user.getId(), showId);
        subscriptionService.replenishIfSubscribed(user.getId(), showId);
        return new WatchedResponse(watchedSyncService.getWatchedEpisodeIds(user.getId(), showId));
    }
}
