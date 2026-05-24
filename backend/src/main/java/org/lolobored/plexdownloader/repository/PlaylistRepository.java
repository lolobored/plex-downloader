package org.lolobored.plexdownloader.repository;

import org.lolobored.plexdownloader.model.Playlist;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface PlaylistRepository extends JpaRepository<Playlist, Long> {
    Optional<Playlist> findByPlexId(String plexId);
}
