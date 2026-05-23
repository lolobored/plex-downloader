package com.plexdownloader.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PathMappingServiceTest {

    @Mock SettingsService settings;
    @InjectMocks PathMappingService service;

    @Test
    void translatesPlexPrefixToAppPrefix() {
        when(settings.getRequired("plex.path.prefix.plex")).thenReturn("/data/media");
        when(settings.getRequired("plex.path.prefix.app")).thenReturn("/mnt");

        String result = service.translate("/data/media/Movies/Inception.mkv");

        assertThat(result).isEqualTo("/mnt/Movies/Inception.mkv");
    }

    @Test
    void throwsWhenPathDoesNotMatchPrefix() {
        when(settings.getRequired("plex.path.prefix.plex")).thenReturn("/data/media");
        when(settings.getRequired("plex.path.prefix.app")).thenReturn("/mnt");

        assertThatThrownBy(() -> service.translate("/other/path/file.mkv"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("/data/media");
    }

    @Test
    void handlesTrailingSlashOnPrefix() {
        when(settings.getRequired("plex.path.prefix.plex")).thenReturn("/data/media/");
        when(settings.getRequired("plex.path.prefix.app")).thenReturn("/mnt");

        String result = service.translate("/data/media/Movies/film.mkv");

        assertThat(result).isEqualTo("/mnt/Movies/film.mkv");
    }
}
