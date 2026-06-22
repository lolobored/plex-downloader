package org.lolobored.plexdownloader.service;

import org.lolobored.plexdownloader.model.QualityProfile;
import org.lolobored.plexdownloader.repository.QualityProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QualityProfileServiceTest {

    @Mock QualityProfileRepository repo;
    @InjectMocks QualityProfileService service;

    private QualityProfile profile(Long id, boolean isDefault) {
        QualityProfile p = new QualityProfile();
        p.setId(id);
        p.setName("p" + id);
        p.setDefault(isDefault);
        return p;
    }

    @Test
    void resolveOrDefault_nullId_returnsDefault() {
        QualityProfile def = profile(1L, true);
        when(repo.findByIsDefaultTrue()).thenReturn(Optional.of(def));
        assertThat(service.resolveOrDefault(null)).isSameAs(def);
    }

    @Test
    void resolveOrDefault_withId_returnsThatProfile() {
        QualityProfile p = profile(5L, false);
        when(repo.findById(5L)).thenReturn(Optional.of(p));
        assertThat(service.resolveOrDefault(5L)).isSameAs(p);
    }

    @Test
    void resolveOrDefault_unknownId_throws() {
        when(repo.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resolveOrDefault(9L))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getDefault_noneConfigured_throws() {
        when(repo.findByIsDefaultTrue()).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getDefault())
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void setDefault_clearsOthersAndSetsTarget() {
        QualityProfile a = profile(1L, true);
        QualityProfile b = profile(2L, false);
        when(repo.findAll()).thenReturn(List.of(a, b));
        when(repo.findById(2L)).thenReturn(Optional.of(b));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.setDefault(2L);

        assertThat(a.isDefault()).isFalse();
        assertThat(b.isDefault()).isTrue();
    }
}
