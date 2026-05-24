package org.lolobored.plexdownloader.repository;

import org.lolobored.plexdownloader.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByPlexAccountId(String plexAccountId);
}
