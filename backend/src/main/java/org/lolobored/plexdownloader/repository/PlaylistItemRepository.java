package org.lolobored.plexdownloader.repository;

import org.lolobored.plexdownloader.model.PlaylistItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Set;

public interface PlaylistItemRepository extends JpaRepository<PlaylistItem, Long> {
    List<PlaylistItem> findByPlaylistIdOrderByOrdinalAsc(Long playlistId);
    List<PlaylistItem> findTop4ByPlaylistIdOrderByOrdinalAsc(Long playlistId);
    @Modifying
    @Transactional
    @Query("DELETE FROM PlaylistItem pi WHERE pi.playlistId = :playlistId AND pi.plexId = :plexId")
    void deleteByPlaylistIdAndPlexId(@Param("playlistId") Long playlistId, @Param("plexId") String plexId);

    @Modifying
    @Transactional
    @Query("DELETE FROM PlaylistItem pi WHERE pi.playlistId = :playlistId")
    void deleteAllByPlaylistId(@Param("playlistId") Long playlistId);

    @Query("SELECT i.plexId FROM PlaylistItem i WHERE i.playlistId = :playlistId")
    Set<String> findPlexIdsByPlaylistId(@Param("playlistId") Long playlistId);
}
