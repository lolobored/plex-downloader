package org.lolobored.plexdownloader.dto;

import org.lolobored.plexdownloader.model.SeasonSubscription;
import java.time.Instant;

public record SeasonSubscriptionResponse(
    Long id,
    Long seasonId,
    Long showId,
    Integer targetCount,
    Instant updatedAt
) {
    public static SeasonSubscriptionResponse from(SeasonSubscription s) {
        return new SeasonSubscriptionResponse(
            s.getId(),
            s.getSeason().getId(),
            s.getSeason().getShow().getId(),
            s.getTargetCount(),
            s.getUpdatedAt()
        );
    }
}
