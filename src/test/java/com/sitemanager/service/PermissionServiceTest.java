package com.sitemanager.service;

import com.sitemanager.model.User;
import com.sitemanager.model.UserGroup;
import com.sitemanager.model.enums.Permission;
import com.sitemanager.model.enums.UserRole;
import com.sitemanager.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PermissionServiceTest {

    private UserRepository userRepository;
    private PermissionService permissionService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        permissionService = new PermissionService(userRepository);
    }

    // --- Unauthenticated ---

    @Test
    void unauthenticated_returnsfalse_forAllPermissions() {
        MockHttpSession session = new MockHttpSession();
        for (Permission p : Permission.values()) {
            assertFalse(permissionService.hasPermission(session, p),
                    "Expected false for " + p + " when unauthenticated");
        }
        verifyNoInteractions(userRepository);
    }

    // --- ROOT_ADMIN ---

    @Test
    void rootAdmin_returnsTrue_forAllPermissions() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("role", "ROOT_ADMIN");
        for (Permission p : Permission.values()) {
            assertTrue(permissionService.hasPermission(session, p),
                    "Expected true for " + p + " when ROOT_ADMIN");
        }
        verifyNoInteractions(userRepository);
    }

    // --- ADMIN ---

    @Test
    void admin_returnsTrue_forAllPermissions() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("role", "ADMIN");
        for (Permission p : Permission.values()) {
            assertTrue(permissionService.hasPermission(session, p),
                    "Expected true for " + p + " when ADMIN");
        }
        verifyNoInteractions(userRepository);
    }

    // --- USER role: missing or bad userId ---

    @Test
    void user_noUserId_returnsFalse() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("role", "USER");
        assertFalse(permissionService.hasPermission(session, Permission.CREATE_SUGGESTIONS));
        verifyNoInteractions(userRepository);
    }

    @Test
    void user_userNotFound_returnsFalse() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("role", "USER");
        session.setAttribute("userId", 42L);
        when(userRepository.findById(42L)).thenReturn(Optional.empty());
        assertFalse(permissionService.hasPermission(session, Permission.CREATE_SUGGESTIONS));
    }

    // --- USER role: not approved ---

    @Test
    void user_notApproved_returnsFalse() {
        User user = userWithGroup(true, true, true, true, true, true, false);
        MockHttpSession session = sessionForUser(user);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        for (Permission p : Permission.values()) {
            assertFalse(permissionService.hasPermission(session, p),
                    "Expected false for " + p + " when user not approved");
        }
    }

    // --- USER role: no group ---

    @Test
    void user_noGroup_returnsFalse() {
        User user = new User("testuser", "hash", UserRole.USER);
        user.setId(10L);
        user.setApproved(true);
        user.setGroup(null);
        MockHttpSession session = sessionForUser(user);
        when(userRepository.findById(10L)).thenReturn(Optional.of(user));
        assertFalse(permissionService.hasPermission(session, Permission.CREATE_SUGGESTIONS));
    }

    // --- USER role: group permission checks ---

    @Test
    void user_approved_canCreateSuggestions_whenGroupAllows() {
        User user = userWithGroup(true, false, false, false, false, false, true);
        MockHttpSession session = sessionForUser(user);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        assertTrue(permissionService.hasPermission(session, Permission.CREATE_SUGGESTIONS));
        assertFalse(permissionService.hasPermission(session, Permission.VOTE));
        assertFalse(permissionService.hasPermission(session, Permission.REPLY));
        assertFalse(permissionService.hasPermission(session, Permission.APPROVE_DENY_SUGGESTIONS));
        assertFalse(permissionService.hasPermission(session, Permission.MANAGE_SETTINGS));
        assertFalse(permissionService.hasPermission(session, Permission.MANAGE_USERS));
    }

    @Test
    void user_approved_canVote_whenGroupAllows() {
        User user = userWithGroup(false, true, false, false, false, false, true);
        MockHttpSession session = sessionForUser(user);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        assertFalse(permissionService.hasPermission(session, Permission.CREATE_SUGGESTIONS));
        assertTrue(permissionService.hasPermission(session, Permission.VOTE));
        assertFalse(permissionService.hasPermission(session, Permission.REPLY));
    }

    @Test
    void user_approved_canReply_whenGroupAllows() {
        User user = userWithGroup(false, false, true, false, false, false, true);
        MockHttpSession session = sessionForUser(user);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        assertFalse(permissionService.hasPermission(session, Permission.VOTE));
        assertTrue(permissionService.hasPermission(session, Permission.REPLY));
    }

    @Test
    void user_approved_canApproveDeny_whenGroupAllows() {
        User user = userWithGroup(false, false, false, true, false, false, true);
        MockHttpSession session = sessionForUser(user);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        assertTrue(permissionService.hasPermission(session, Permission.APPROVE_DENY_SUGGESTIONS));
        assertFalse(permissionService.hasPermission(session, Permission.MANAGE_SETTINGS));
    }

    @Test
    void user_approved_canManageSettings_whenGroupAllows() {
        User user = userWithGroup(false, false, false, false, true, false, true);
        MockHttpSession session = sessionForUser(user);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        assertTrue(permissionService.hasPermission(session, Permission.MANAGE_SETTINGS));
        assertFalse(permissionService.hasPermission(session, Permission.MANAGE_USERS));
    }

    @Test
    void user_approved_canManageUsers_whenGroupAllows() {
        User user = userWithGroup(false, false, false, false, false, true, true);
        MockHttpSession session = sessionForUser(user);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        assertTrue(permissionService.hasPermission(session, Permission.MANAGE_USERS));
        assertFalse(permissionService.hasPermission(session, Permission.MANAGE_SETTINGS));
    }

    @Test
    void user_approved_noGroupPermissions_returnsFalseForAll() {
        User user = userWithGroup(false, false, false, false, false, false, true);
        MockHttpSession session = sessionForUser(user);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        for (Permission p : Permission.values()) {
            assertFalse(permissionService.hasPermission(session, p),
                    "Expected false for " + p + " when group has no permissions");
        }
    }

    // --- helpers ---

    private static long idCounter = 100L;

    private User userWithGroup(boolean canCreate, boolean canVote, boolean canReply,
                                boolean canApproveDeny, boolean canManageSettings, boolean canManageUsers,
                                boolean approved) {
        UserGroup group = new UserGroup("TestGroup", canCreate, canVote, canReply,
                canApproveDeny, canManageSettings, canManageUsers);
        User user = new User("user" + idCounter, "hash", UserRole.USER);
        user.setId(idCounter++);
        user.setApproved(approved);
        user.setGroup(group);
        return user;
    }

    private MockHttpSession sessionForUser(User user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("role", "USER");
        session.setAttribute("userId", user.getId());
        session.setAttribute("username", user.getUsername());
        return session;
    }
}
