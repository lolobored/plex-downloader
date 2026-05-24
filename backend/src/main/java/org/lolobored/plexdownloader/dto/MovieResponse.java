package org.lolobored.plexdownloader.dto;

import org.lolobored.plexdownloader.model.Movie;
import java.util.List;

public record MovieResponse(
    Long id, String plexId, String title, Integer year, String summary,
    String posterUrl, Float rating, String studio, Long durationMs,
    List<String> genres, List<String> directors, List<ActorDto> actors,
    boolean watched
) {
    public record ActorDto(Long id, String name) {}

    public static MovieResponse from(Movie m, boolean watched) {
        List<ActorDto> actors = m.getActors() == null ? List.of() :
            m.getActors().stream().map(a -> new ActorDto(a.getId(), a.getName())).toList();
        return new MovieResponse(m.getId(), m.getPlexId(), m.getTitle(), m.getYear(),
            m.getSummary(), m.getPosterUrl(), m.getRating(), m.getStudio(), m.getDurationMs(),
            m.getGenres(), m.getDirectors(), actors, watched);
    }

    public static MovieResponse from(Movie m) { return from(m, false); }
}
