package com.plexdownloader.repository;

import com.plexdownloader.model.Actor;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ActorRepository extends JpaRepository<Actor, Long> {
    Optional<Actor> findByPlexId(String plexId);
}
