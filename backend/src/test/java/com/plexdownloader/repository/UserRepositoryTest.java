package com.plexdownloader.repository;

import com.plexdownloader.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
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
