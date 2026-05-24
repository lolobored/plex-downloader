package org.lolobored.plexdownloader.repository;

import org.lolobored.plexdownloader.model.ShowSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ShowSubscriptionRepository extends JpaRepository<ShowSubscription, Long> {

    List<ShowSubscription> findByUserId(Long userId);

    Optional<ShowSubscription> findByUserIdAndShowId(Long userId, Long showId);

    @Query("SELECT s FROM ShowSubscription s JOIN FETCH s.user JOIN FETCH s.show")
    List<ShowSubscription> findAllWithUserAndShow();
}
