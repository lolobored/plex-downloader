package com.plexdownloader.repository;

import com.plexdownloader.model.DownloadQueueItem;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.*;

public interface DownloadQueueRepository extends JpaRepository<DownloadQueueItem, Long> {

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
}
