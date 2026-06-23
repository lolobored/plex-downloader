package org.lolobored.plexdownloader.repository;

import org.lolobored.plexdownloader.model.Episode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface EpisodeRepository extends JpaRepository<Episode, Long> {
    Optional<Episode> findByPlexId(String plexId);
    List<Episode> findBySeasonIdOrderByEpisodeNumber(Long seasonId);
    List<Episode> findBySubtitlesScannedAtIsNull();
    List<Episode> findBySubtitlesScannedAtIsNullAndFilePathIsNotNull();

    @Query("SELECT e FROM Episode e JOIN FETCH e.season s JOIN FETCH s.show WHERE e.id IN :ids")
    List<Episode> findWithSeasonAndShowByIdIn(@Param("ids") Collection<Long> ids);

    @Query("SELECT e FROM Episode e WHERE " +
           "e.season.id = :seasonId AND " +
           "(:none = false OR e.subtitleLangs = ',') AND " +
           "(:has IS NULL OR e.subtitleLangs LIKE CONCAT('%', :has, '%')) AND " +
           "(:missing IS NULL OR (e.subtitleLangs IS NOT NULL AND e.subtitleLangs NOT LIKE CONCAT('%', :missing, '%'))) " +
           "ORDER BY e.episodeNumber")
    List<Episode> findBySeasonIdFilteredBySubtitles(@Param("seasonId") Long seasonId,
                                                    @Param("none") boolean none,
                                                    @Param("has") String has,
                                                    @Param("missing") String missing);

    @Query("SELECT e FROM Episode e WHERE " +
           "(:none = false OR e.subtitleLangs = ',') AND " +
           "(:has IS NULL OR e.subtitleLangs LIKE CONCAT('%', :has, '%')) AND " +
           "(:missing IS NULL OR (e.subtitleLangs IS NOT NULL AND e.subtitleLangs NOT LIKE CONCAT('%', :missing, '%')))")
    List<Episode> findFilteredBySubtitles(@Param("none") boolean none,
                                          @Param("has") String has,
                                          @Param("missing") String missing);

    @Query("SELECT DISTINCT e.season.show.id FROM Episode e WHERE e.season.show.id IN :showIds AND e.subtitleLangs = ','")
    Set<Long> findShowIdsWithMissingSubtitles(@Param("showIds") Collection<Long> showIds);

    @Query("SELECT DISTINCT e.season.id FROM Episode e WHERE e.season.id IN :seasonIds AND e.subtitleLangs = ','")
    Set<Long> findSeasonIdsWithMissingSubtitles(@Param("seasonIds") Collection<Long> seasonIds);
}
