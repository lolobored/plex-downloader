package org.lolobored.plexdownloader.repository;

import org.lolobored.plexdownloader.model.Episode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface EpisodeRepository extends JpaRepository<Episode, Long> {
    Optional<Episode> findByPlexId(String plexId);
    List<Episode> findBySeasonIdOrderByEpisodeNumber(Long seasonId);

    @Query("SELECT e FROM Episode e JOIN FETCH e.season s JOIN FETCH s.show WHERE e.id IN :ids")
    List<Episode> findWithSeasonAndShowByIdIn(@Param("ids") Collection<Long> ids);
}
