package com.sitemanager.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemanager.dto.GroupRequest;
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
class GroupControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserGroupRepository userGroupRepository;

    @Autowired
    private UserRepository userRepository;

    private MockHttpSession adminSession;
    private MockHttpSession userSession;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        // Remove non-default groups
        userGroupRepository.findAll().stream()
                .filter(g -> !UserGroupService.DEFAULT_GROUP_NAME.equals(g.getName()))
                .forEach(userGroupRepository::delete);
        // Ensure the default group exists
        if (userGroupRepository.findByName(UserGroupService.DEFAULT_GROUP_NAME).isEmpty()) {
            userGroupRepository.save(new UserGroup(UserGroupService.DEFAULT_GROUP_NAME, true, true, true, false, false, false));
        }

        adminSession = new MockHttpSession();
        adminSession.setAttribute("username", "admin");
        adminSession.setAttribute("role", "ADMIN");
        adminSession.setAttribute("userId", 1L);

        userSession = new MockHttpSession();
        userSession.setAttribute("username", "regularuser");
        userSession.setAttribute("role", "USER");
        userSession.setAttribute("userId", 2L);
    }

    // --- GET /api/groups ---

    @Test
    void listGroups_asAdmin_returnsGroupList() throws Exception {
        mockMvc.perform(get("/api/groups").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void listGroups_asRootAdmin_returnsGroupList() throws Exception {
        MockHttpSession rootSession = new MockHttpSession();
        rootSession.setAttribute("role", "ROOT_ADMIN");

        mockMvc.perform(get("/api/groups").session(rootSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void listGroups_asRegularUser_returns403() throws Exception {
        mockMvc.perform(get("/api/groups").session(userSession))
                .andExpect(status().isForbidden());
    }

    @Test
    void listGroups_unauthenticated_returns403() throws Exception {
        mockMvc.perform(get("/api/groups"))
                .andExpect(status().isForbidden());
    }

    // --- POST /api/groups ---

    @Test
    void createGroup_asAdmin_createsGroup() throws Exception {
        GroupRequest request = buildRequest("Moderators", true, true, true, true, false, false);

        mockMvc.perform(post("/api/groups")
                        .session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Moderators"))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.canCreateSuggestions").value(true))
                .andExpect(jsonPath("$.canVote").value(true))
                .andExpect(jsonPath("$.canApproveDenySuggestions").value(true));
    }

    @Test
    void createGroup_asRegularUser_returns403() throws Exception {
        GroupRequest request = buildRequest("ShouldFail", false, false, false, false, false, false);

        mockMvc.perform(post("/api/groups")
                        .session(userSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void createGroup_duplicateName_returns400() throws Exception {
        GroupRequest request = buildRequest("DuplicateGroup", false, false, false, false, false, false);
        mockMvc.perform(post("/api/groups")
                .session(adminSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

        mockMvc.perform(post("/api/groups")
                        .session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void createGroup_blankName_returns400() throws Exception {
        GroupRequest request = new GroupRequest();
        request.setName("");

        mockMvc.perform(post("/api/groups")
                        .session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    // --- GET /api/groups/{id} ---

    @Test
    void getGroup_existingId_returnsGroup() throws Exception {
        UserGroup group = userGroupRepository.save(new UserGroup("ReadableGroup", true, false, false, false, false, false));

        mockMvc.perform(get("/api/groups/" + group.getId()).session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("ReadableGroup"))
                .andExpect(jsonPath("$.canCreateSuggestions").value(true));
    }

    @Test
    void getGroup_nonExistentId_returns404() throws Exception {
        mockMvc.perform(get("/api/groups/99999").session(adminSession))
                .andExpect(status().isNotFound());
    }

    @Test
    void getGroup_asRegularUser_returns403() throws Exception {
        UserGroup group = userGroupRepository.save(new UserGroup("AnotherGroup", false, false, false, false, false, false));

        mockMvc.perform(get("/api/groups/" + group.getId()).session(userSession))
                .andExpect(status().isForbidden());
    }

    // --- PUT /api/groups/{id} ---

    @Test
    void updateGroup_asAdmin_updatesPermissions() throws Exception {
        UserGroup group = userGroupRepository.save(new UserGroup("ToUpdate", false, false, false, false, false, false));
        GroupRequest request = buildRequest("Updated Name", true, true, false, false, true, false);

        mockMvc.perform(put("/api/groups/" + group.getId())
                        .session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Name"))
                .andExpect(jsonPath("$.canCreateSuggestions").value(true))
                .andExpect(jsonPath("$.canManageSettings").value(true));
    }

    @Test
    void updateGroup_nonExistentId_returns400() throws Exception {
        GroupRequest request = buildRequest("Any", false, false, false, false, false, false);

        mockMvc.perform(put("/api/groups/99999")
                        .session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateGroup_asRegularUser_returns403() throws Exception {
        UserGroup group = userGroupRepository.save(new UserGroup("UpdateFail", false, false, false, false, false, false));
        GroupRequest request = buildRequest("UpdateFail", true, false, false, false, false, false);

        mockMvc.perform(put("/api/groups/" + group.getId())
                        .session(userSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    // --- DELETE /api/groups/{id} ---

    @Test
    void deleteGroup_regularGroup_deletesIt() throws Exception {
        UserGroup group = userGroupRepository.save(new UserGroup("ToDelete", false, false, false, false, false, false));

        mockMvc.perform(delete("/api/groups/" + group.getId()).session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Group deleted"));
    }

    @Test
    void deleteGroup_defaultGroup_returns400() throws Exception {
        UserGroup defaultGroup = userGroupRepository.findByName(UserGroupService.DEFAULT_GROUP_NAME)
                .orElseThrow();

        mockMvc.perform(delete("/api/groups/" + defaultGroup.getId()).session(adminSession))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void deleteGroup_groupWithUsers_returns400() throws Exception {
        UserGroup group = userGroupRepository.save(new UserGroup("OccupiedGroup", true, true, true, false, false, false));
        User user = new User("testuser", "hash", UserRole.USER);
        user.setGroup(group);
        user.setApproved(true);
        userRepository.save(user);

        mockMvc.perform(delete("/api/groups/" + group.getId()).session(adminSession))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void deleteGroup_nonExistentId_returns400() throws Exception {
        mockMvc.perform(delete("/api/groups/99999").session(adminSession))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteGroup_asRegularUser_returns403() throws Exception {
        UserGroup group = userGroupRepository.save(new UserGroup("DeleteFail", false, false, false, false, false, false));

        mockMvc.perform(delete("/api/groups/" + group.getId()).session(userSession))
                .andExpect(status().isForbidden());
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
