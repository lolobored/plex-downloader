package org.lolobored.plexdownloader.repository;

import org.lolobored.plexdownloader.model.UserEpisodeWatched;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;

public interface UserEpisodeWatchedRepository extends JpaRepository<UserEpisodeWatched, Long> {

    Optional<UserEpisodeWatched> findByUserIdAndEpisodeId(Long userId, Long episodeId);

    @Query("SELECT w.episode.id FROM UserEpisodeWatched w " +
           "WHERE w.user.id = :userId AND w.episode.season.show.id = :showId")
    Set<Long> findWatchedEpisodeIds(@Param("userId") Long userId, @Param("showId") Long showId);

    @Query("SELECT MAX(w.syncedAt) FROM UserEpisodeWatched w " +
           "WHERE w.user.id = :userId AND w.episode.season.show.id = :showId")
    Optional<Instant> findLastSyncAt(@Param("userId") Long userId, @Param("showId") Long showId);

    /** Returns IDs of shows (from the given set) where every episode is watched by the user. */
    @Query(value = """
        SELECT s.show_id
        FROM seasons s
        JOIN episodes e ON e.season_id = s.id
        WHERE s.show_id IN (:showIds)
        GROUP BY s.show_id
        HAVING COUNT(e.id) > 0
          AND COUNT(e.id) = (
            SELECT COUNT(*) FROM user_episode_watched uew
            JOIN episodes e2 ON e2.id = uew.episode_id
            JOIN seasons  s2 ON s2.id = e2.season_id
            WHERE s2.show_id = s.show_id AND uew.user_id = :userId
          )
        """, nativeQuery = true)
    Set<Long> findFullyWatchedShowIds(@Param("userId") Long userId, @Param("showIds") Collection<Long> showIds);

    /** Returns IDs of seasons (from the given set) where every episode is watched by the user. */
    @Query(value = """
        SELECT e.season_id
        FROM episodes e
        WHERE e.season_id IN (:seasonIds)
        GROUP BY e.season_id
        HAVING COUNT(e.id) > 0
          AND COUNT(e.id) = (
            SELECT COUNT(*) FROM user_episode_watched uew
            JOIN episodes e2 ON e2.id = uew.episode_id
            WHERE e2.season_id = e.season_id AND uew.user_id = :userId
          )
        """, nativeQuery = true)
    Set<Long> findFullyWatchedSeasonIds(@Param("userId") Long userId, @Param("seasonIds") Collection<Long> seasonIds);
}
