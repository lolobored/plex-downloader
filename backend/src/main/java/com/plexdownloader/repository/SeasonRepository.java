package com.plexdownloader.repository;

import com.plexdownloader.model.Season;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface SeasonRepository extends JpaRepository<Season, Long> {
    Optional<Season> findByPlexId(String plexId);
    List<Season> findByShowIdOrderBySeasonNumber(Long showId);
}
