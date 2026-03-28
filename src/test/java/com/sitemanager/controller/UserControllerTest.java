package com.sitemanager.controller;

import com.sitemanager.model.User;
import com.sitemanager.model.UserGroup;
import com.sitemanager.model.enums.UserRole;
import com.sitemanager.repository.UserGroupRepository;
import com.sitemanager.repository.UserRepository;
import com.sitemanager.service.UserGroupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserGroupRepository userGroupRepository;

    private MockHttpSession adminSession;
    private MockHttpSession userSession;
    private UserGroup defaultGroup;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        userGroupRepository.findAll().stream()
                .filter(g -> !UserGroupService.DEFAULT_GROUP_NAME.equals(g.getName()))
                .forEach(userGroupRepository::delete);

        defaultGroup = userGroupRepository.findByName(UserGroupService.DEFAULT_GROUP_NAME)
                .orElseGet(() -> userGroupRepository.save(
                        new UserGroup(UserGroupService.DEFAULT_GROUP_NAME, true, true, true, false, false, false)));

        adminSession = new MockHttpSession();
        adminSession.setAttribute("username", "admin");
        adminSession.setAttribute("role", "ADMIN");
        adminSession.setAttribute("userId", 999L);

        userSession = new MockHttpSession();
        userSession.setAttribute("username", "regularuser");
        userSession.setAttribute("role", "USER");
        userSession.setAttribute("userId", 998L);
    }

    // --- GET /api/users ---

    @Test
    void listUsers_asAdmin_returnsUserList() throws Exception {
        User u = createUser("alice", true, false);
        mockMvc.perform(get("/api/users").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].username").value("alice"));
    }

    @Test
    void listUsers_asRootAdmin_returnsUserList() throws Exception {
        MockHttpSession rootSession = new MockHttpSession();
        rootSession.setAttribute("role", "ROOT_ADMIN");
        mockMvc.perform(get("/api/users").session(rootSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void listUsers_asRegularUser_returns403() throws Exception {
        mockMvc.perform(get("/api/users").session(userSession))
                .andExpect(status().isForbidden());
    }

    @Test
    void listUsers_unauthenticated_returns403() throws Exception {
        mockMvc.perform(get("/api/users"))
                .andExpect(status().isForbidden());
    }

    @Test
    void listUsers_includesGroupName() throws Exception {
        User u = createUser("bob", true, false);
        mockMvc.perform(get("/api/users").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].groupName").value(UserGroupService.DEFAULT_GROUP_NAME));
    }

    // --- GET /api/users/pending ---

    @Test
    void listPendingUsers_returnsPendingOnly() throws Exception {
        createUser("pending1", false, false);
        createUser("approved1", true, false);
        createUser("denied1", false, true);

        mockMvc.perform(get("/api/users/pending").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].username").value("pending1"));
    }

    @Test
    void listPendingUsers_asRegularUser_returns403() throws Exception {
        mockMvc.perform(get("/api/users/pending").session(userSession))
                .andExpect(status().isForbidden());
    }

    // --- POST /api/users/{id}/approve ---

    @Test
    void approveUser_setsApprovedTrue() throws Exception {
        User u = createUser("toApprove", false, false);

        mockMvc.perform(post("/api/users/" + u.getId() + "/approve").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(true))
                .andExpect(jsonPath("$.denied").value(false));
    }

    @Test
    void approveUser_nonExistentId_returns400() throws Exception {
        mockMvc.perform(post("/api/users/99999/approve").session(adminSession))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void approveUser_asRegularUser_returns403() throws Exception {
        User u = createUser("toApprove2", false, false);
        mockMvc.perform(post("/api/users/" + u.getId() + "/approve").session(userSession))
                .andExpect(status().isForbidden());
    }

    // --- POST /api/users/{id}/deny ---

    @Test
    void denyUser_setsApprovedFalseAndDeniedTrue() throws Exception {
        User u = createUser("toDeny", false, false);

        mockMvc.perform(post("/api/users/" + u.getId() + "/deny").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(false))
                .andExpect(jsonPath("$.denied").value(true));
    }

    @Test
    void denyUser_doesNotDeleteUserRecord() throws Exception {
        User u = createUser("toDenyPersist", false, false);
        long id = u.getId();

        mockMvc.perform(post("/api/users/" + id + "/deny").session(adminSession))
                .andExpect(status().isOk());

        // User still exists
        mockMvc.perform(get("/api/users").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.username == 'toDenyPersist')]").exists());
    }

    @Test
    void denyUser_nonExistentId_returns400() throws Exception {
        mockMvc.perform(post("/api/users/99999/deny").session(adminSession))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void denyUser_asRegularUser_returns403() throws Exception {
        User u = createUser("toDeny2", false, false);
        mockMvc.perform(post("/api/users/" + u.getId() + "/deny").session(userSession))
                .andExpect(status().isForbidden());
    }

    // --- PUT /api/users/{id}/group ---

    @Test
    void assignGroup_reassignsUserToNewGroup() throws Exception {
        User u = createUser("toReassign", true, false);
        UserGroup newGroup = userGroupRepository.save(
                new UserGroup("PowerUsers", true, true, true, false, false, false));

        String body = "{\"groupId\": " + newGroup.getId() + "}";

        mockMvc.perform(put("/api/users/" + u.getId() + "/group")
                        .session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupName").value("PowerUsers"));
    }

    @Test
    void assignGroup_nonExistentUser_returns400() throws Exception {
        String body = "{\"groupId\": " + defaultGroup.getId() + "}";
        mockMvc.perform(put("/api/users/99999/group")
                        .session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void assignGroup_nonExistentGroup_returns400() throws Exception {
        User u = createUser("reassignFail", true, false);
        String body = "{\"groupId\": 99999}";
        mockMvc.perform(put("/api/users/" + u.getId() + "/group")
                        .session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void assignGroup_missingGroupId_returns400() throws Exception {
        User u = createUser("reassignNoId", true, false);
        mockMvc.perform(put("/api/users/" + u.getId() + "/group")
                        .session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void assignGroup_asRegularUser_returns403() throws Exception {
        User u = createUser("reassignForbidden", true, false);
        String body = "{\"groupId\": " + defaultGroup.getId() + "}";
        mockMvc.perform(put("/api/users/" + u.getId() + "/group")
                        .session(userSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    // --- helpers ---

    private User createUser(String username, boolean approved, boolean denied) {
        User u = new User(username, "hash", UserRole.USER);
        u.setGroup(defaultGroup);
        u.setApproved(approved);
        u.setDenied(denied);
        return userRepository.save(u);
    }
}
