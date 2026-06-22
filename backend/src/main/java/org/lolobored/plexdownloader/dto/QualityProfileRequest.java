package org.lolobored.plexdownloader.dto;

import org.lolobored.plexdownloader.model.QualityProfile;

public record QualityProfileRequest(
    String name,
    QualityProfile.Codec codec,
    QualityProfile.Container container,
    int qualityLevel,
    QualityProfile.ResolutionCap resolutionCap,
    QualityProfile.AudioMode audioMode
) {
    public QualityProfile toEntity() {
        QualityProfile p = new QualityProfile();
        p.setName(name);
        p.setCodec(codec);
        p.setContainer(container);
        p.setQualityLevel(qualityLevel);
        p.setResolutionCap(resolutionCap);
        p.setAudioMode(audioMode);
        return p;
    }
}
