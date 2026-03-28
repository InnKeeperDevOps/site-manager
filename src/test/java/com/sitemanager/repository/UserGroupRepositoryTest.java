package com.sitemanager.repository;

import com.sitemanager.model.UserGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class UserGroupRepositoryTest {

    @Autowired
    private UserGroupRepository userGroupRepository;

    @BeforeEach
    void setUp() {
        userGroupRepository.deleteAll();
    }

    @Test
    void save_persistsAllPermissionFields() {
        UserGroup group = new UserGroup("Test Group", true, true, true, false, false, false);
        UserGroup saved = userGroupRepository.save(group);

        assertNotNull(saved.getId());
        assertEquals("Test Group", saved.getName());
        assertTrue(saved.isCanCreateSuggestions());
        assertTrue(saved.isCanVote());
        assertTrue(saved.isCanReply());
        assertFalse(saved.isCanApproveDenySuggestions());
        assertFalse(saved.isCanManageSettings());
        assertFalse(saved.isCanManageUsers());
    }

    @Test
    void findByName_returnsGroupWhenExists() {
        userGroupRepository.save(new UserGroup("Editors", false, true, true, false, false, false));

        Optional<UserGroup> found = userGroupRepository.findByName("Editors");

        assertTrue(found.isPresent());
        assertEquals("Editors", found.get().getName());
    }

    @Test
    void findByName_returnsEmptyWhenNotExists() {
        Optional<UserGroup> found = userGroupRepository.findByName("Nonexistent");

        assertFalse(found.isPresent());
    }

    @Test
    void save_defaultPermissionsAreFalse() {
        UserGroup group = new UserGroup();
        group.setName("Minimal Group");
        UserGroup saved = userGroupRepository.save(group);

        assertFalse(saved.isCanCreateSuggestions());
        assertFalse(saved.isCanVote());
        assertFalse(saved.isCanReply());
        assertFalse(saved.isCanApproveDenySuggestions());
        assertFalse(saved.isCanManageSettings());
        assertFalse(saved.isCanManageUsers());
    }

    @Test
    void update_permissionFieldsCanBeChanged() {
        UserGroup group = new UserGroup("Updatable", false, false, false, false, false, false);
        group = userGroupRepository.save(group);

        group.setCanCreateSuggestions(true);
        group.setCanVote(true);
        UserGroup updated = userGroupRepository.save(group);

        assertTrue(updated.isCanCreateSuggestions());
        assertTrue(updated.isCanVote());
    }
}
