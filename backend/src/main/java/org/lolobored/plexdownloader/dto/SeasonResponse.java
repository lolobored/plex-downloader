package org.lolobored.plexdownloader.dto;

import org.lolobored.plexdownloader.model.Season;

public record SeasonResponse(
    Long id, String plexId, Integer seasonNumber, String title,
    String posterUrl, Integer episodeCount, boolean watched
) {
    public static SeasonResponse from(Season s, boolean watched) {
        return new SeasonResponse(s.getId(), s.getPlexId(), s.getSeasonNumber(),
            s.getTitle(), s.getPosterUrl(), s.getEpisodeCount(), watched);
    }

    public static SeasonResponse from(Season s) { return from(s, false); }
}
