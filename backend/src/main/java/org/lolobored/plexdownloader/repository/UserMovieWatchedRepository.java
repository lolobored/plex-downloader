package org.lolobored.plexdownloader.repository;

import org.lolobored.plexdownloader.model.UserMovieWatched;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

public interface UserMovieWatchedRepository extends JpaRepository<UserMovieWatched, Long> {

    Optional<UserMovieWatched> findByUserIdAndMovieId(Long userId, Long movieId);

    @Query("SELECT w.movie.id FROM UserMovieWatched w WHERE w.user.id = :userId AND w.movie.id IN :movieIds")
    Set<Long> findWatchedMovieIds(@Param("userId") Long userId, @Param("movieIds") Collection<Long> movieIds);
}
