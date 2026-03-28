package com.sitemanager.config;

import com.sitemanager.model.User;
import com.sitemanager.model.UserGroup;
import com.sitemanager.model.enums.UserRole;
import com.sitemanager.repository.UserGroupRepository;
import com.sitemanager.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class DataMigrationRunnerTest {

    @Autowired
    private UserGroupRepository userGroupRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DataMigrationRunner dataMigrationRunner;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void registeredUserGroup_existsAfterStartup() {
        Optional<UserGroup> group = userGroupRepository.findByName("Registered User");

        assertTrue(group.isPresent(), "Default 'Registered User' group should be seeded on startup");
    }

    @Test
    void registeredUserGroup_hasCorrectPermissions() {
        UserGroup group = userGroupRepository.findByName("Registered User").orElseThrow();

        assertTrue(group.isCanCreateSuggestions(), "Registered users should be able to create suggestions");
        assertTrue(group.isCanVote(), "Registered users should be able to vote");
        assertTrue(group.isCanReply(), "Registered users should be able to reply");
        assertFalse(group.isCanApproveDenySuggestions(), "Registered users should not be able to approve/deny suggestions");
        assertFalse(group.isCanManageSettings(), "Registered users should not be able to manage settings");
        assertFalse(group.isCanManageUsers(), "Registered users should not be able to manage users");
    }

    @Test
    void seedRegisteredUserGroup_isIdempotent() {
        // Running migration again should not create a duplicate group
        dataMigrationRunner.run();

        long count = userGroupRepository.findAll().stream()
                .filter(g -> "Registered User".equals(g.getName()))
                .count();
        assertEquals(1, count, "Migration should be idempotent — exactly one 'Registered User' group");
    }

    @Test
    void existingUserRoleUsers_areAssignedToRegisteredUserGroup_onMigration() {
        // Create a USER-role user with no group
        User user = new User("migration-test-user", "hash", UserRole.USER);
        user = userRepository.save(user);
        assertNull(user.getGroup(), "Newly saved USER should start without a group");

        // Running the migration should assign the group
        dataMigrationRunner.run();

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertNotNull(reloaded.getGroup(), "USER-role user should be assigned to 'Registered User' group after migration");
        assertEquals("Registered User", reloaded.getGroup().getName());

        // cleanup
        userRepository.delete(reloaded);
    }
}
