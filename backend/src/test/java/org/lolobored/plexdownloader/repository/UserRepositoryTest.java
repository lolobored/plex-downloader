package org.lolobored.plexdownloader.repository;

import org.lolobored.plexdownloader.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class UserRepositoryTest {

    @Autowired UserRepository repo;

    @Test
    void saveAndFindByPlexAccountId() {
        User u = new User();
        u.setPlexAccountId("plex-123");
        u.setUsername("Laurent");
        u.setRole(User.Role.ADMIN);
        repo.save(u);

        Optional<User> found = repo.findByPlexAccountId("plex-123");
        assertThat(found).isPresent();
        assertThat(found.get().getUsername()).isEqualTo("Laurent");
        assertThat(found.get().getRole()).isEqualTo(User.Role.ADMIN);
    }

    @Test
    void countReturnsZeroOnEmptyTable() {
        assertThat(repo.count()).isZero();
    }
}
