package org.lolobored.plexdownloader.controller;

import org.lolobored.plexdownloader.dto.*;
import org.lolobored.plexdownloader.model.Episode;
import org.lolobored.plexdownloader.model.Movie;
import org.lolobored.plexdownloader.model.User;
import org.lolobored.plexdownloader.repository.*;
import org.lolobored.plexdownloader.util.SubtitleLangs;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequiredArgsConstructor
public class LibraryController {

    private final MovieRepository movieRepo;
    private final TvShowRepository showRepo;
    private final SeasonRepository seasonRepo;
    private final EpisodeRepository episodeRepo;
    private final UserMovieWatchedRepository movieWatchedRepo;
    private final UserEpisodeWatchedRepository episodeWatchedRepo;

    // ── Movies ────────────────────────────────────────────────────────────────

    @GetMapping("/api/movies")
    public Page<MovieResponse> getMovies(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Integer year,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String subtitles,
            @RequestParam(required = false) String hasLang,
            @RequestParam(required = false) String missingLang) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("title"));
        boolean none = "none".equalsIgnoreCase(subtitles);
        String hasToken     = hasLang     != null ? SubtitleLangs.token(hasLang)     : null;
        String missingToken = missingLang != null ? SubtitleLangs.token(missingLang) : null;
        Page<Movie> movies;
        if (none || hasToken != null || missingToken != null) {
            movies = movieRepo.searchFiltered(
                search != null ? search : "", year, none, hasToken, missingToken, pageable);
        } else {
            movies = movieRepo.search(search != null ? search : "", year, pageable);
        }
        User user = currentUser();
        if (user == null || movies.isEmpty()) return movies.map(MovieResponse::from);
        List<Long> ids = movies.getContent().stream().map(m -> m.getId()).toList();
        Set<Long> watched = movieWatchedRepo.findWatchedMovieIds(user.getId(), ids);
        return movies.map(m -> MovieResponse.from(m, watched.contains(m.getId())));
    }

    @GetMapping("/api/movies/{id}")
    public ResponseEntity<MovieResponse> getMovie(@PathVariable Long id) {
        User user = currentUser();
        return movieRepo.findById(id)
            .map(m -> {
                boolean watched = user != null
                    && movieWatchedRepo.findByUserIdAndMovieId(user.getId(), m.getId()).isPresent();
                return ResponseEntity.ok(MovieResponse.from(m, watched));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    // ── TV Shows ──────────────────────────────────────────────────────────────

    @GetMapping("/api/tv")
    public Page<TvShowResponse> getShows(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Integer year,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("title"));
        var shows = showRepo.search(search != null ? search : "", year, pageable);
        User user = currentUser();
        if (user == null || shows.isEmpty()) return shows.map(TvShowResponse::from);
        List<Long> ids = shows.getContent().stream().map(s -> s.getId()).toList();
        Set<Long> watched = episodeWatchedRepo.findFullyWatchedShowIds(user.getId(), ids);
        return shows.map(s -> TvShowResponse.from(s, watched.contains(s.getId())));
    }

    @GetMapping("/api/tv/{showId}")
    public ResponseEntity<TvShowResponse> getShow(@PathVariable Long showId) {
        User user = currentUser();
        return showRepo.findById(showId)
            .map(s -> {
                boolean watched = user != null
                    && !episodeWatchedRepo.findFullyWatchedShowIds(user.getId(), List.of(s.getId())).isEmpty();
                return ResponseEntity.ok(TvShowResponse.from(s, watched));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    // ── Seasons ───────────────────────────────────────────────────────────────

    @GetMapping("/api/tv/{showId}/seasons")
    public List<SeasonResponse> getSeasons(@PathVariable Long showId) {
        var seasons = seasonRepo.findByShowIdOrderBySeasonNumber(showId);
        User user = currentUser();
        if (user == null || seasons.isEmpty()) return seasons.stream().map(SeasonResponse::from).toList();
        List<Long> ids = seasons.stream().map(s -> s.getId()).toList();
        Set<Long> watched = episodeWatchedRepo.findFullyWatchedSeasonIds(user.getId(), ids);
        return seasons.stream().map(s -> SeasonResponse.from(s, watched.contains(s.getId()))).toList();
    }

    @GetMapping("/api/tv/{showId}/seasons/{seasonId}")
    public ResponseEntity<SeasonResponse> getSeason(@PathVariable Long showId,
                                                     @PathVariable Long seasonId) {
        User user = currentUser();
        return seasonRepo.findById(seasonId)
            .map(s -> {
                boolean watched = user != null
                    && !episodeWatchedRepo.findFullyWatchedSeasonIds(user.getId(), List.of(s.getId())).isEmpty();
                return ResponseEntity.ok(SeasonResponse.from(s, watched));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    // ── Episodes ──────────────────────────────────────────────────────────────

    @GetMapping("/api/tv/{showId}/seasons/{seasonId}/episodes")
    public List<EpisodeResponse> getEpisodes(@PathVariable Long showId,
                                              @PathVariable Long seasonId,
                                              @RequestParam(required = false) String subtitles,
                                              @RequestParam(required = false) String hasLang,
                                              @RequestParam(required = false) String missingLang) {
        boolean none = "none".equalsIgnoreCase(subtitles);
        String hasToken     = hasLang     != null ? SubtitleLangs.token(hasLang)     : null;
        String missingToken = missingLang != null ? SubtitleLangs.token(missingLang) : null;
        List<Episode> episodes;
        if (none || hasToken != null || missingToken != null) {
            episodes = episodeRepo.findBySeasonIdFilteredBySubtitles(
                seasonId, none, hasToken, missingToken);
        } else {
            episodes = episodeRepo.findBySeasonIdOrderByEpisodeNumber(seasonId);
        }
        return episodes.stream().map(EpisodeResponse::from).toList();
    }

    @GetMapping("/api/tv/{showId}/seasons/{seasonId}/episodes/{episodeId}")
    public ResponseEntity<EpisodeResponse> getEpisode(@PathVariable Long showId,
                                                       @PathVariable Long seasonId,
                                                       @PathVariable Long episodeId) {
        return episodeRepo.findById(episodeId)
            .map(e -> ResponseEntity.ok(EpisodeResponse.from(e)))
            .orElse(ResponseEntity.notFound().build());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private User currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return null;
        Object p = auth.getPrincipal();
        return p instanceof User ? (User) p : null;
    }
}
