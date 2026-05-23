package com.plexdownloader.repository;

import com.plexdownloader.model.TvShow;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface TvShowRepository extends JpaRepository<TvShow, Long> {
    Optional<TvShow> findByPlexId(String plexId);

    @Query("SELECT t FROM TvShow t WHERE " +
           "(:search IS NULL OR LOWER(t.title) LIKE LOWER(CONCAT('%',:search,'%'))) AND " +
           "(:year IS NULL OR t.year = :year)")
    Page<TvShow> search(@Param("search") String search,
                        @Param("year") Integer year,
                        Pageable pageable);
}
