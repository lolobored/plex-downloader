package org.lolobored.plexdownloader.repository;

import org.lolobored.plexdownloader.model.PlaylistItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Set;

public interface PlaylistItemRepository extends JpaRepository<PlaylistItem, Long> {
    List<PlaylistItem> findByPlaylistIdOrderByOrdinalAsc(Long playlistId);
    List<PlaylistItem> findTop4ByPlaylistIdOrderByOrdinalAsc(Long playlistId);
    void deleteByPlaylistIdAndPlexId(Long playlistId, String plexId);

    @Query("SELECT i.plexId FROM PlaylistItem i WHERE i.playlistId = :playlistId")
    Set<String> findPlexIdsByPlaylistId(@Param("playlistId") Long playlistId);
}
