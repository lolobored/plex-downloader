package org.lolobored.plexdownloader.dto;

import org.lolobored.plexdownloader.model.Episode;
import java.time.LocalDate;

public record EpisodeResponse(
    Long id, String plexId, Integer episodeNumber, String title, String summary,
    String thumbnailUrl, Long durationMs, LocalDate airDate,
    String director, String writer, String videoResolution,
    String subtitleLangs, Boolean subtitlesScanned
) {
    public static EpisodeResponse from(Episode e) {
        return new EpisodeResponse(e.getId(), e.getPlexId(), e.getEpisodeNumber(),
            e.getTitle(), e.getSummary(), e.getThumbnailUrl(), e.getDurationMs(),
            e.getAirDate(), e.getDirector(), e.getWriter(), e.getVideoResolution(),
            e.getSubtitleLangs(), e.getSubtitlesScannedAt() != null);
    }
}
