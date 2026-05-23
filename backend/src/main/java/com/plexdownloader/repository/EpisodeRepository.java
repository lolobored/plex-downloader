package com.plexdownloader.repository;

import com.plexdownloader.model.Episode;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface EpisodeRepository extends JpaRepository<Episode, Long> {
    Optional<Episode> findByPlexId(String plexId);
    List<Episode> findBySeasonIdOrderByEpisodeNumber(Long seasonId);
}
