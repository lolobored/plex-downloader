package org.lolobored.plexdownloader.repository;

import org.lolobored.plexdownloader.model.DownloadQueueItem;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.*;

public interface DownloadQueueRepository extends JpaRepository<DownloadQueueItem, Long> {

    List<DownloadQueueItem> findByStatusAndTdarrStatusNotIn(
        DownloadQueueItem.Status status,
        Collection<DownloadQueueItem.TdarrStatus> tdarrStatuses
    );

    List<DownloadQueueItem> findAllByOrderByQueuePositionAsc();

    @Query("SELECT i FROM DownloadQueueItem i WHERE i.status = 'IN_PROGRESS'")
    Optional<DownloadQueueItem> findInProgress();

    @Query("SELECT MAX(i.queuePosition) FROM DownloadQueueItem i WHERE i.status = 'PENDING'")
    Optional<Integer> findMaxQueuePosition();

    @Query("SELECT i.mediaId FROM DownloadQueueItem i " +
           "WHERE i.user.id = :userId AND i.mediaType = 'EPISODE' " +
           "AND i.status IN ('PENDING', 'IN_PROGRESS', 'DONE') " +
           "AND i.mediaId IN (SELECT e.id FROM Episode e WHERE e.season.show.id = :showId)")
    Set<Long> findActiveEpisodeIdsForShow(@Param("userId") Long userId, @Param("showId") Long showId);

    boolean existsByUser_IdAndMediaTypeAndMediaId(Long userId, DownloadQueueItem.MediaType type, Long mediaId);

    Optional<DownloadQueueItem> findByUser_IdAndMediaTypeAndMediaId(
        Long userId, DownloadQueueItem.MediaType type, Long mediaId);

    List<DownloadQueueItem> findByStatus(DownloadQueueItem.Status status);

    List<DownloadQueueItem> findByStatusOrderByQueuePositionAsc(DownloadQueueItem.Status status);

    @Query("SELECT i FROM DownloadQueueItem i WHERE i.user.id = :userId AND i.mediaType = 'EPISODE' " +
           "AND i.mediaId IN (SELECT e.id FROM Episode e WHERE e.season.show.id = :showId)")
    List<DownloadQueueItem> findAllByUserIdAndShowId(@Param("userId") Long userId,
                                                      @Param("showId") Long showId);

    @Query("SELECT i FROM DownloadQueueItem i WHERE i.user.id = :userId AND (" +
           "  (i.mediaType = 'MOVIE' AND i.mediaId IN (" +
           "    SELECT m.id FROM Movie m WHERE m.plexId IN (" +
           "      SELECT pi.plexId FROM PlaylistItem pi WHERE pi.playlistId = :playlistId AND pi.mediaType = 'MOVIE'))) " +
           "  OR " +
           "  (i.mediaType = 'EPISODE' AND i.mediaId IN (" +
           "    SELECT e.id FROM Episode e WHERE e.plexId IN (" +
           "      SELECT pi.plexId FROM PlaylistItem pi WHERE pi.playlistId = :playlistId AND pi.mediaType = 'EPISODE'))))")
    List<DownloadQueueItem> findAllByUserIdAndPlaylistId(@Param("userId") Long userId,
                                                         @Param("playlistId") Long playlistId);
}
