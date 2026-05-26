package org.lolobored.plexdownloader.repository;

import org.lolobored.plexdownloader.model.SeasonSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SeasonSubscriptionRepository extends JpaRepository<SeasonSubscription, Long> {

    List<SeasonSubscription> findByUserId(Long userId);

    Optional<SeasonSubscription> findByUserIdAndSeasonId(Long userId, Long seasonId);

    @Query("SELECT s FROM SeasonSubscription s JOIN FETCH s.user JOIN FETCH s.season ss JOIN FETCH ss.show")
    List<SeasonSubscription> findAllWithUserAndSeason();
}
