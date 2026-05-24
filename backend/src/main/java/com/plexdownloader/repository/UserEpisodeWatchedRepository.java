package com.plexdownloader.repository;

import com.plexdownloader.model.UserEpisodeWatched;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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
}
