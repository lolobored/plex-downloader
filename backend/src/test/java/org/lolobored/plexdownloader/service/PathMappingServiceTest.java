package org.lolobored.plexdownloader.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PathMappingServiceTest {

    @Mock SettingsService settings;
    @InjectMocks PathMappingService service;

    private void stubPrefixes(String moviesPlex, String moviesApp, String tvPlex, String tvApp) {
        when(settings.getRequired("plex.path.prefix.movies.plex")).thenReturn(moviesPlex);
        when(settings.getRequired("plex.path.prefix.movies.app")).thenReturn(moviesApp);
        when(settings.getRequired("plex.path.prefix.tv.plex")).thenReturn(tvPlex);
        when(settings.getRequired("plex.path.prefix.tv.app")).thenReturn(tvApp);
    }

    // ── translate ────────────────────────────────────────────────────────────

    @Test
    void translate_moviesPath() {
        stubPrefixes("/movies", "/movies", "/tv", "/tvshows");
        assertThat(service.translate("/movies/Inception/Inception.mkv"))
            .isEqualTo("/movies/Inception/Inception.mkv");
    }

    @Test
    void translate_tvPath() {
        stubPrefixes("/movies", "/movies", "/tv", "/tvshows");
        assertThat(service.translate("/tv/Breaking Bad/S01E01.mkv"))
            .isEqualTo("/tvshows/Breaking Bad/S01E01.mkv");
    }

    @Test
    void translate_trailingSlashStripped() {
        stubPrefixes("/movies/", "/movies", "/tv/", "/tvshows");
        assertThat(service.translate("/movies/film.mkv")).isEqualTo("/movies/film.mkv");
    }

    @Test
    void translate_throwsWhenNoPrefixMatches() {
        stubPrefixes("/movies", "/movies", "/tv", "/tvshows");
        assertThatThrownBy(() -> service.translate("/unknown/file.mkv"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not match");
    }

    // ── appToTdarr ──────────────────────────────────────────────────────────

    @Test
    void appToTdarr_translatesConversionPath() {
        when(settings.getRequired("plex.conversion.dir")).thenReturn("/conversion");
        when(settings.getRequired("tdarr.path.prefix.conversion")).thenReturn("/media/plex-download");

        assertThat(service.appToTdarr("/conversion/in-flight/movies/film.mkv"))
            .isEqualTo("/media/plex-download/in-flight/movies/film.mkv");
    }

    @Test
    void appToTdarr_stripsTrailingSlash() {
        when(settings.getRequired("plex.conversion.dir")).thenReturn("/conversion/");
        when(settings.getRequired("tdarr.path.prefix.conversion")).thenReturn("/media/plex-download/");

        assertThat(service.appToTdarr("/conversion/in-flight/film.mkv"))
            .isEqualTo("/media/plex-download/in-flight/film.mkv");
    }

    @Test
    void appToTdarr_throwsWhenPathNotUnderConversionDir() {
        when(settings.getRequired("plex.conversion.dir")).thenReturn("/conversion");
        when(settings.getRequired("tdarr.path.prefix.conversion")).thenReturn("/media/plex-download");

        assertThatThrownBy(() -> service.appToTdarr("/movies/film.mkv"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("conversion dir");
    }

    // ── tdarrToApp ──────────────────────────────────────────────────────────

    @Test
    void tdarrToApp_translatesOutputPath() {
        when(settings.getRequired("tdarr.path.prefix.conversion")).thenReturn("/media/plex-download");
        when(settings.getRequired("plex.conversion.dir")).thenReturn("/conversion");

        assertThat(service.tdarrToApp("/media/plex-download/libraries/movies/film.mp4"))
            .isEqualTo("/conversion/libraries/movies/film.mp4");
    }

    @Test
    void tdarrToApp_throwsWhenPathNotUnderTdarrPrefix() {
        when(settings.getRequired("tdarr.path.prefix.conversion")).thenReturn("/media/plex-download");
        when(settings.getRequired("plex.conversion.dir")).thenReturn("/conversion");

        assertThatThrownBy(() -> service.tdarrToApp("/other/path/film.mp4"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Tdarr prefix");
    }
}
