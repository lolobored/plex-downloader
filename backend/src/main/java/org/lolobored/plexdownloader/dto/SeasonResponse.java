package org.lolobored.plexdownloader.dto;

import org.lolobored.plexdownloader.model.Season;

public record SeasonResponse(
    Long id, String plexId, Integer seasonNumber, String title,
    String posterUrl, Integer episodeCount, boolean watched,
    Boolean hasEpisodesMissingSubtitles
) {
    public static SeasonResponse from(Season s, boolean watched, boolean hasEpisodesMissingSubtitles) {
        return new SeasonResponse(s.getId(), s.getPlexId(), s.getSeasonNumber(),
            s.getTitle(), s.getPosterUrl(), s.getEpisodeCount(), watched, hasEpisodesMissingSubtitles);
    }

    public static SeasonResponse from(Season s, boolean watched) { return from(s, watched, false); }

    public static SeasonResponse from(Season s) { return from(s, false, false); }
}
