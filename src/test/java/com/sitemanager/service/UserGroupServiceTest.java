package com.sitemanager.service;

import com.sitemanager.dto.GroupRequest;
import com.sitemanager.model.User;
import com.sitemanager.model.UserGroup;
import com.sitemanager.model.enums.UserRole;
import com.sitemanager.repository.UserGroupRepository;
import com.sitemanager.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class UserGroupServiceTest {

    @Autowired
    private UserGroupService userGroupService;

    @Autowired
    private UserGroupRepository userGroupRepository;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        // Remove any groups except the default one to keep a clean state
        userGroupRepository.findAll().stream()
                .filter(g -> !UserGroupService.DEFAULT_GROUP_NAME.equals(g.getName()))
                .forEach(userGroupRepository::delete);
        // Ensure the default group exists
        if (userGroupRepository.findByName(UserGroupService.DEFAULT_GROUP_NAME).isEmpty()) {
            userGroupRepository.save(new UserGroup(UserGroupService.DEFAULT_GROUP_NAME, true, true, true, false, false, false));
        }
    }

    // --- findAll ---

    @Test
    void findAll_returnsAllGroups() {
        userGroupRepository.save(new UserGroup("Editors", true, false, true, false, false, false));
        List<UserGroup> all = userGroupService.findAll();
        assertTrue(all.size() >= 2);
    }

    // --- findById ---

    @Test
    void findById_existingGroup_returnsGroup() {
        UserGroup saved = userGroupRepository.save(new UserGroup("Viewers", false, false, false, false, false, false));
        Optional<UserGroup> found = userGroupService.findById(saved.getId());
        assertTrue(found.isPresent());
        assertEquals("Viewers", found.get().getName());
    }

    @Test
    void findById_nonExistentId_returnsEmpty() {
        Optional<UserGroup> found = userGroupService.findById(99999L);
        assertFalse(found.isPresent());
    }

    // --- create ---

    @Test
    void create_newGroup_persistsWithPermissions() {
        GroupRequest request = buildRequest("Power Users", true, true, true, true, false, false);
        UserGroup created = userGroupService.create(request);

        assertNotNull(created.getId());
        assertEquals("Power Users", created.getName());
        assertTrue(created.isCanCreateSuggestions());
        assertTrue(created.isCanVote());
        assertTrue(created.isCanReply());
        assertTrue(created.isCanApproveDenySuggestions());
        assertFalse(created.isCanManageSettings());
        assertFalse(created.isCanManageUsers());
    }

    @Test
    void create_duplicateName_throwsException() {
        GroupRequest request = buildRequest("Duplicate Group", false, false, false, false, false, false);
        userGroupService.create(request);

        GroupRequest duplicate = buildRequest("Duplicate Group", true, true, true, false, false, false);
        assertThrows(IllegalArgumentException.class, () -> userGroupService.create(duplicate));
    }

    // --- update ---

    @Test
    void update_existingGroup_changesPermissions() {
        UserGroup group = userGroupRepository.save(new UserGroup("Old Name", false, false, false, false, false, false));
        GroupRequest request = buildRequest("New Name", true, true, false, false, true, false);

        UserGroup updated = userGroupService.update(group.getId(), request);

        assertEquals("New Name", updated.getName());
        assertTrue(updated.isCanCreateSuggestions());
        assertTrue(updated.isCanVote());
        assertFalse(updated.isCanReply());
        assertTrue(updated.isCanManageSettings());
    }

    @Test
    void update_nonExistentId_throwsException() {
        GroupRequest request = buildRequest("Any Name", false, false, false, false, false, false);
        assertThrows(IllegalArgumentException.class, () -> userGroupService.update(99999L, request));
    }

    @Test
    void update_renameToExistingNameOfDifferentGroup_throwsException() {
        userGroupRepository.save(new UserGroup("Taken Name", false, false, false, false, false, false));
        UserGroup other = userGroupRepository.save(new UserGroup("Other Group", false, false, false, false, false, false));

        GroupRequest request = buildRequest("Taken Name", false, false, false, false, false, false);
        assertThrows(IllegalArgumentException.class, () -> userGroupService.update(other.getId(), request));
    }

    @Test
    void update_keepSameName_succeeds() {
        UserGroup group = userGroupRepository.save(new UserGroup("Same Name", false, false, false, false, false, false));
        GroupRequest request = buildRequest("Same Name", true, false, false, false, false, false);

        UserGroup updated = userGroupService.update(group.getId(), request);
        assertEquals("Same Name", updated.getName());
        assertTrue(updated.isCanCreateSuggestions());
    }

    // --- delete ---

    @Test
    void delete_regularGroup_removesIt() {
        UserGroup group = userGroupRepository.save(new UserGroup("Temp Group", false, false, false, false, false, false));
        Long id = group.getId();

        userGroupService.delete(id);

        assertFalse(userGroupRepository.findById(id).isPresent());
    }

    @Test
    void delete_defaultRegisteredUserGroup_throwsException() {
        UserGroup defaultGroup = userGroupRepository.findByName(UserGroupService.DEFAULT_GROUP_NAME)
                .orElseThrow();
        assertThrows(IllegalStateException.class, () -> userGroupService.delete(defaultGroup.getId()));
    }

    @Test
    void delete_groupWithUsersAssigned_throwsException() {
        UserGroup group = userGroupRepository.save(new UserGroup("Occupied Group", true, true, true, false, false, false));
        User user = new User("testuser", "hash", UserRole.USER);
        user.setGroup(group);
        user.setApproved(true);
        userRepository.save(user);

        assertThrows(IllegalStateException.class, () -> userGroupService.delete(group.getId()));
    }

    @Test
    void delete_nonExistentId_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> userGroupService.delete(99999L));
    }

    // --- helpers ---

    private GroupRequest buildRequest(String name, boolean createSuggestions, boolean vote, boolean reply,
                                      boolean approveDeny, boolean manageSettings, boolean manageUsers) {
        GroupRequest r = new GroupRequest();
        r.setName(name);
        r.setCanCreateSuggestions(createSuggestions);
        r.setCanVote(vote);
        r.setCanReply(reply);
        r.setCanApproveDenySuggestions(approveDeny);
        r.setCanManageSettings(manageSettings);
        r.setCanManageUsers(manageUsers);
        return r;
    }
}
