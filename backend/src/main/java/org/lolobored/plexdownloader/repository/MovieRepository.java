package org.lolobored.plexdownloader.repository;

import org.lolobored.plexdownloader.model.Movie;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MovieRepository extends JpaRepository<Movie, Long> {
    Optional<Movie> findByPlexId(String plexId);
    List<Movie> findByPlexIdNotIn(Collection<String> plexIds);
    List<Movie> findBySubtitlesScannedAtIsNull();
    List<Movie> findBySubtitlesScannedAtIsNullAndFilePathIsNotNull();

    @Query("SELECT m FROM Movie m WHERE " +
           "(:search = '' OR LOWER(m.title) LIKE LOWER(CONCAT('%',:search,'%'))) AND " +
           "(:year IS NULL OR m.year = :year)")
    Page<Movie> search(@Param("search") String search,
                       @Param("year") Integer year,
                       Pageable pageable);

    @Query("SELECT m FROM Movie m WHERE " +
           "(:search = '' OR LOWER(m.title) LIKE LOWER(CONCAT('%',:search,'%'))) AND " +
           "(:year IS NULL OR m.year = :year) AND " +
           "(:none = false OR m.subtitleLangs = ',') AND " +
           "(:has IS NULL OR m.subtitleLangs LIKE CONCAT('%', :has, '%')) AND " +
           "(:missing IS NULL OR (m.subtitleLangs IS NOT NULL AND m.subtitleLangs NOT LIKE CONCAT('%', :missing, '%')))")
    Page<Movie> searchFiltered(@Param("search") String search,
                               @Param("year") Integer year,
                               @Param("none") boolean none,
                               @Param("has") String has,
                               @Param("missing") String missing,
                               Pageable pageable);

    @Query("SELECT m FROM Movie m WHERE " +
           "(:none = false OR m.subtitleLangs = ',') AND " +
           "(:has IS NULL OR m.subtitleLangs LIKE CONCAT('%', :has, '%')) AND " +
           "(:missing IS NULL OR (m.subtitleLangs IS NOT NULL AND m.subtitleLangs NOT LIKE CONCAT('%', :missing, '%')))")
    List<Movie> findFilteredBySubtitles(@Param("none") boolean none,
                                        @Param("has") String has,
                                        @Param("missing") String missing);
}
