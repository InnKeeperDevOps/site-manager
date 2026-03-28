package com.sitemanager.service;

import com.sitemanager.model.User;
import com.sitemanager.model.UserGroup;
import com.sitemanager.model.enums.UserRole;
import com.sitemanager.repository.UserGroupRepository;
import com.sitemanager.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserGroupRepository userGroupRepository;

    @Mock
    private SlackNotificationService slackNotificationService;

    @InjectMocks
    private UserService userService;

    private UserGroup group;
    private User pendingUser;
    private User approvedUser;
    private User deniedUser;

    @BeforeEach
    void setUp() {
        group = new UserGroup("Registered User", true, true, true, false, false, false);

        pendingUser = new User("pending", "hash", UserRole.USER);
        pendingUser.setGroup(group);
        pendingUser.setApproved(false);
        pendingUser.setDenied(false);

        approvedUser = new User("approved", "hash", UserRole.USER);
        approvedUser.setGroup(group);
        approvedUser.setApproved(true);
        approvedUser.setDenied(false);

        deniedUser = new User("denied", "hash", UserRole.USER);
        deniedUser.setGroup(group);
        deniedUser.setApproved(false);
        deniedUser.setDenied(true);
    }

    @Test
    void findAll_returnsAllUsers() {
        when(userRepository.findAll()).thenReturn(List.of(pendingUser, approvedUser, deniedUser));
        assertThat(userService.findAll()).hasSize(3);
    }

    @Test
    void findPending_returnsOnlyPendingUsers() {
        when(userRepository.findByApprovedFalseAndDeniedFalse()).thenReturn(List.of(pendingUser));
        List<User> result = userService.findPending();
        assertThat(result).containsExactly(pendingUser);
    }

    @Test
    void approve_setsApprovedTrueAndDeniedFalse() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(pendingUser));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(slackNotificationService.sendUserApprovedNotification(any())).thenReturn(null);

        User result = userService.approve(1L);

        assertThat(result.isApproved()).isTrue();
        assertThat(result.isDenied()).isFalse();
        verify(slackNotificationService).sendUserApprovedNotification("pending");
    }

    @Test
    void approve_userNotFound_throwsIllegalArgumentException() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> userService.approve(99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("User not found");
    }

    @Test
    void deny_setsApprovedFalseAndDeniedTrue() {
        when(userRepository.findById(2L)).thenReturn(Optional.of(pendingUser));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(slackNotificationService.sendUserDeniedNotification(any())).thenReturn(null);

        User result = userService.deny(2L);

        assertThat(result.isApproved()).isFalse();
        assertThat(result.isDenied()).isTrue();
        verify(slackNotificationService).sendUserDeniedNotification("pending");
    }

    @Test
    void deny_userNotFound_throwsIllegalArgumentException() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> userService.deny(99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("User not found");
    }

    @Test
    void deny_doesNotDeleteUser() {
        when(userRepository.findById(2L)).thenReturn(Optional.of(pendingUser));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(slackNotificationService.sendUserDeniedNotification(any())).thenReturn(null);

        userService.deny(2L);

        verify(userRepository, never()).delete(any());
        verify(userRepository, never()).deleteById(any());
    }

    @Test
    void assignGroup_reassignsUserGroup() {
        UserGroup newGroup = new UserGroup("PowerUsers", true, true, true, false, false, false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(pendingUser));
        when(userGroupRepository.findById(10L)).thenReturn(Optional.of(newGroup));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        User result = userService.assignGroup(1L, 10L);
        assertThat(result.getGroup()).isEqualTo(newGroup);
    }

    @Test
    void assignGroup_userNotFound_throwsIllegalArgumentException() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> userService.assignGroup(99L, 10L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("User not found");
    }

    @Test
    void assignGroup_groupNotFound_throwsIllegalArgumentException() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(pendingUser));
        when(userGroupRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> userService.assignGroup(1L, 99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Group not found");
    }
}
