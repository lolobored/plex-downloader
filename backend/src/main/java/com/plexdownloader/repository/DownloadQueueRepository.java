package com.plexdownloader.repository;

import com.plexdownloader.model.DownloadQueueItem;
import org.springframework.data.jpa.repository.*;
import java.util.*;

public interface DownloadQueueRepository extends JpaRepository<DownloadQueueItem, Long> {
    List<DownloadQueueItem> findAllByOrderByQueuePositionAsc();

    @Query("SELECT i FROM DownloadQueueItem i WHERE i.status = 'IN_PROGRESS'")
    Optional<DownloadQueueItem> findInProgress();

    @Query("SELECT MAX(i.queuePosition) FROM DownloadQueueItem i WHERE i.status = 'PENDING'")
    Optional<Integer> findMaxQueuePosition();
}
