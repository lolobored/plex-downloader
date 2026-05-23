package com.plexdownloader.service;

import com.plexdownloader.model.Setting;
import com.plexdownloader.repository.SettingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SettingsServiceTest {

    @Mock SettingRepository repo;
    @InjectMocks SettingsService service;

    @Test
    void getReturnsValueFromRepo() {
        Setting s = new Setting();
        s.setKey("plex.server.url");
        s.setValue("http://localhost:32400");
        when(repo.findById("plex.server.url")).thenReturn(Optional.of(s));

        assertThat(service.get("plex.server.url")).contains("http://localhost:32400");
    }

    @Test
    void getMissingKeyReturnsEmpty() {
        when(repo.findById("missing")).thenReturn(Optional.empty());
        assertThat(service.get("missing")).isEmpty();
    }

    @Test
    void setSavesToRepo() {
        service.set("plex.server.url", "http://plex:32400");
        Setting expected = new Setting();
        expected.setKey("plex.server.url");
        expected.setValue("http://plex:32400");
        verify(repo).save(expected);
    }

    @Test
    void getRequiredThrowsWhenMissing() {
        when(repo.findById("plex.server.url")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getRequired("plex.server.url"))
            .isInstanceOf(IllegalStateException.class);
    }
}
