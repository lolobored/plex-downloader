package org.lolobored.plexdownloader.dto;

import org.lolobored.plexdownloader.model.ShowSubscription;
import java.time.Instant;

public record SubscriptionResponse(Long id, Long showId, Integer targetCount, Instant updatedAt) {
    public static SubscriptionResponse from(ShowSubscription s) {
        return new SubscriptionResponse(
            s.getId(), s.getShow().getId(), s.getTargetCount(), s.getUpdatedAt());
    }
}
