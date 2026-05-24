package org.lolobored.plexdownloader.controller;

import org.lolobored.plexdownloader.dto.*;
import org.lolobored.plexdownloader.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class LibraryController {

    private final MovieRepository movieRepo;
    private final TvShowRepository showRepo;
    private final SeasonRepository seasonRepo;
    private final EpisodeRepository episodeRepo;

    @GetMapping("/api/movies")
    public Page<MovieResponse> getMovies(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Integer year,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("title"));
        return movieRepo.search(search, year, pageable).map(MovieResponse::from);
    }

    @GetMapping("/api/movies/{id}")
    public ResponseEntity<MovieResponse> getMovie(@PathVariable Long id) {
        return movieRepo.findById(id)
            .map(m -> ResponseEntity.ok(MovieResponse.from(m)))
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/api/tv")
    public Page<TvShowResponse> getShows(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Integer year,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("title"));
        return showRepo.search(search, year, pageable).map(TvShowResponse::from);
    }

    @GetMapping("/api/tv/{showId}")
    public ResponseEntity<TvShowResponse> getShow(@PathVariable Long showId) {
        return showRepo.findById(showId)
            .map(s -> ResponseEntity.ok(TvShowResponse.from(s)))
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/api/tv/{showId}/seasons")
    public List<SeasonResponse> getSeasons(@PathVariable Long showId) {
        return seasonRepo.findByShowIdOrderBySeasonNumber(showId)
            .stream().map(SeasonResponse::from).toList();
    }

    @GetMapping("/api/tv/{showId}/seasons/{seasonId}")
    public ResponseEntity<SeasonResponse> getSeason(@PathVariable Long showId,
                                                     @PathVariable Long seasonId) {
        return seasonRepo.findById(seasonId)
            .map(s -> ResponseEntity.ok(SeasonResponse.from(s)))
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/api/tv/{showId}/seasons/{seasonId}/episodes")
    public List<EpisodeResponse> getEpisodes(@PathVariable Long showId,
                                              @PathVariable Long seasonId) {
        return episodeRepo.findBySeasonIdOrderByEpisodeNumber(seasonId)
            .stream().map(EpisodeResponse::from).toList();
    }

    @GetMapping("/api/tv/{showId}/seasons/{seasonId}/episodes/{episodeId}")
    public ResponseEntity<EpisodeResponse> getEpisode(@PathVariable Long showId,
                                                       @PathVariable Long seasonId,
                                                       @PathVariable Long episodeId) {
        return episodeRepo.findById(episodeId)
            .map(e -> ResponseEntity.ok(EpisodeResponse.from(e)))
            .orElse(ResponseEntity.notFound().build());
    }
}
