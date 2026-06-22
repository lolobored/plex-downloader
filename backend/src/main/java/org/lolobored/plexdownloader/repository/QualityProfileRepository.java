package org.lolobored.plexdownloader.repository;

import org.lolobored.plexdownloader.model.QualityProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface QualityProfileRepository extends JpaRepository<QualityProfile, Long> {
    Optional<QualityProfile> findByIsDefaultTrue();
}
