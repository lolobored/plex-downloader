package com.plexdownloader.repository;

import com.plexdownloader.model.Movie;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface MovieRepository extends JpaRepository<Movie, Long> {
    Optional<Movie> findByPlexId(String plexId);

    @Query("SELECT m FROM Movie m WHERE " +
           "(:search IS NULL OR LOWER(m.title) LIKE LOWER(CONCAT('%',:search,'%'))) AND " +
           "(:year IS NULL OR m.year = :year)")
    Page<Movie> search(@Param("search") String search,
                       @Param("year") Integer year,
                       Pageable pageable);
}
