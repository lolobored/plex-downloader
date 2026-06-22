package org.lolobored.plexdownloader.service;

import org.lolobored.plexdownloader.model.QualityProfile;
import org.lolobored.plexdownloader.repository.QualityProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class QualityProfileService {

    private final QualityProfileRepository repo;

    public List<QualityProfile> findAll() {
        return repo.findAll();
    }

    public QualityProfile getDefault() {
        return repo.findByIsDefaultTrue()
            .orElseThrow(() -> new IllegalStateException("No default quality profile configured"));
    }

    public QualityProfile resolveOrDefault(Long id) {
        if (id == null) return getDefault();
        return repo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Quality profile not found: " + id));
    }

    public QualityProfile create(QualityProfile p) {
        p.setId(null);
        return repo.save(p);
    }

    @Transactional
    public QualityProfile update(Long id, QualityProfile p) {
        QualityProfile existing = repo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Quality profile not found: " + id));
        existing.setName(p.getName());
        existing.setCodec(p.getCodec());
        existing.setContainer(p.getContainer());
        existing.setQualityLevel(p.getQualityLevel());
        existing.setResolutionCap(p.getResolutionCap());
        existing.setAudioMode(p.getAudioMode());
        return repo.save(existing);
    }

    public void delete(Long id) {
        repo.deleteById(id);
    }

    @Transactional
    public QualityProfile setDefault(Long id) {
        QualityProfile target = repo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Quality profile not found: " + id));
        for (QualityProfile p : repo.findAll()) {
            if (p.isDefault() && !p.getId().equals(id)) {
                p.setDefault(false);
                repo.save(p);
            }
        }
        target.setDefault(true);
        return repo.save(target);
    }
}
