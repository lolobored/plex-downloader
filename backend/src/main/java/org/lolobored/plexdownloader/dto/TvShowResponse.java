package org.lolobored.plexdownloader.dto;

import org.lolobored.plexdownloader.model.TvShow;
import java.util.List;

public record TvShowResponse(
    Long id, String plexId, String title, Integer year, String summary,
    String posterUrl, Float rating, Integer totalSeasons,
    List<String> genres, List<MovieResponse.ActorDto> actors
) {
    public static TvShowResponse from(TvShow s) {
        List<MovieResponse.ActorDto> actors = s.getActors() == null ? List.of() :
            s.getActors().stream().map(a -> new MovieResponse.ActorDto(a.getId(), a.getName())).toList();
        return new TvShowResponse(s.getId(), s.getPlexId(), s.getTitle(), s.getYear(),
            s.getSummary(), s.getPosterUrl(), s.getRating(), s.getTotalSeasons(),
            s.getGenres(), actors);
    }
}
