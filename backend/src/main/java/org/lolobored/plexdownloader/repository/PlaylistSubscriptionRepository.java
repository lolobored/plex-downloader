package org.lolobored.plexdownloader.repository;

import org.lolobored.plexdownloader.model.PlaylistSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

public interface PlaylistSubscriptionRepository extends JpaRepository<PlaylistSubscription, Long> {

    @Query("SELECT s FROM PlaylistSubscription s JOIN FETCH s.user WHERE s.playlist.id = :playlistId")
    List<PlaylistSubscription> findByPlaylistIdWithUser(@Param("playlistId") Long playlistId);

    @Query("SELECT CASE WHEN COUNT(s) > 0 THEN true ELSE false END " +
           "FROM PlaylistSubscription s WHERE s.user.id = :userId AND s.playlist.id = :playlistId")
    boolean existsByUserIdAndPlaylistId(@Param("userId") Long userId, @Param("playlistId") Long playlistId);

    @Modifying
    @Transactional
    @Query("DELETE FROM PlaylistSubscription s WHERE s.user.id = :userId AND s.playlist.id = :playlistId")
    void deleteByUserIdAndPlaylistId(@Param("userId") Long userId, @Param("playlistId") Long playlistId);

    @Modifying
    @Transactional
    void deleteByPlaylistId(Long playlistId);
}
