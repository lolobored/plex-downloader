package org.lolobored.plexdownloader.controller;

import org.lolobored.plexdownloader.dto.QualityProfileRequest;
import org.lolobored.plexdownloader.model.QualityProfile;
import org.lolobored.plexdownloader.service.QualityProfileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QualityProfileControllerTest {

    @Mock QualityProfileService service;
    @InjectMocks QualityProfileController controller;

    @Test
    void list_returnsProfiles() {
        QualityProfile p = new QualityProfile(); p.setId(1L); p.setName("Default");
        when(service.findAll()).thenReturn(List.of(p));
        assertThat(controller.list()).hasSize(1);
    }

    @Test
    void create_delegatesToService() {
        QualityProfileRequest req = new QualityProfileRequest("HD", QualityProfile.Codec.HEVC_QSV,
            QualityProfile.Container.MKV, 22, QualityProfile.ResolutionCap.P1080, QualityProfile.AudioMode.COPY);
        when(service.create(any())).thenAnswer(inv -> inv.getArgument(0));
        QualityProfile created = controller.create(req);
        assertThat(created.getName()).isEqualTo("HD");
        assertThat(created.getQualityLevel()).isEqualTo(22);
    }

    @Test
    void setDefault_delegates() {
        QualityProfile p = new QualityProfile(); p.setId(3L); p.setDefault(true);
        when(service.setDefault(3L)).thenReturn(p);
        assertThat(controller.setDefault(3L).isDefault()).isTrue();
    }
}
