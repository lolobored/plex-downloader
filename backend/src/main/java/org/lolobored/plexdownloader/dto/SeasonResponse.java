package org.lolobored.plexdownloader.dto;

import org.lolobored.plexdownloader.model.Season;

public record SeasonResponse(
    Long id, String plexId, Integer seasonNumber, String title,
    String posterUrl, Integer episodeCount
) {
    public static SeasonResponse from(Season s) {
        return new SeasonResponse(s.getId(), s.getPlexId(), s.getSeasonNumber(),
            s.getTitle(), s.getPosterUrl(), s.getEpisodeCount());
    }
}
