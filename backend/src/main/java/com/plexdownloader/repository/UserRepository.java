package com.plexdownloader.repository;

import com.plexdownloader.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByPlexAccountId(String plexAccountId);
}
