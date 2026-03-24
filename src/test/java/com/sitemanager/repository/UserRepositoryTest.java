package com.sitemanager.repository;

import com.sitemanager.model.User;
import com.sitemanager.model.enums.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
    }

    @Test
    void findByUsername_existingUser_returnsUser() {
        userRepository.save(new User("testuser", "hash", UserRole.ADMIN));

        Optional<User> found = userRepository.findByUsername("testuser");
        assertTrue(found.isPresent());
        assertEquals("testuser", found.get().getUsername());
    }

    @Test
    void findByUsername_nonExistent_returnsEmpty() {
        Optional<User> found = userRepository.findByUsername("nobody");
        assertFalse(found.isPresent());
    }

    @Test
    void existsByRole_whenRoleExists_returnsTrue() {
        userRepository.save(new User("admin", "hash", UserRole.ROOT_ADMIN));
        assertTrue(userRepository.existsByRole(UserRole.ROOT_ADMIN));
    }

    @Test
    void existsByRole_whenNoRole_returnsFalse() {
        assertFalse(userRepository.existsByRole(UserRole.ROOT_ADMIN));
    }

    @Test
    void findByRole_returnsCorrectUsers() {
        userRepository.save(new User("admin1", "hash", UserRole.ADMIN));
        userRepository.save(new User("admin2", "hash", UserRole.ADMIN));
        userRepository.save(new User("user1", "hash", UserRole.USER));

        List<User> admins = userRepository.findByRole(UserRole.ADMIN);
        assertEquals(2, admins.size());
    }

    @Test
    void save_setsCreatedAt() {
        User user = new User("test", "hash", UserRole.USER);
        User saved = userRepository.save(user);
        assertNotNull(saved.getCreatedAt());
    }
}
