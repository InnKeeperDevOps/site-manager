package com.sitemanager.service;

import com.sitemanager.dto.LoginRequest;
import com.sitemanager.dto.RegisterRequest;
import com.sitemanager.dto.SetupRequest;
import com.sitemanager.exception.AccountDeniedException;
import com.sitemanager.exception.AccountPendingApprovalException;
import com.sitemanager.model.SiteSettings;
import com.sitemanager.model.User;
import com.sitemanager.model.UserGroup;
import com.sitemanager.model.enums.UserRole;
import com.sitemanager.repository.SiteSettingsRepository;
import com.sitemanager.repository.UserGroupRepository;
import com.sitemanager.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class AuthServiceTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserGroupRepository userGroupRepository;

    @Autowired
    private SiteSettingsRepository siteSettingsRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        // Ensure the default 'Registered User' group exists (another test class may have deleted it)
        if (userGroupRepository.findByName("Registered User").isEmpty()) {
            userGroupRepository.save(new UserGroup("Registered User", true, true, true, false, false, false));
        }
        // Reset requireRegistrationApproval to false (default) before each test
        SiteSettings settings = siteSettingsRepository.findAll().stream()
                .findFirst().orElseGet(SiteSettings::new);
        settings.setRequireRegistrationApproval(false);
        siteSettingsRepository.save(settings);
    }

    @Test
    void isSetupRequired_whenNoRootAdmin_returnsTrue() {
        assertTrue(authService.isSetupRequired());
    }

    @Test
    void isSetupRequired_whenRootAdminExists_returnsFalse() {
        userRepository.save(new User("admin", passwordEncoder.encode("pass"), UserRole.ROOT_ADMIN));
        assertFalse(authService.isSetupRequired());
    }

    @Test
    void setupRootAdmin_createsAdminSuccessfully() {
        SetupRequest request = new SetupRequest();
        request.setUsername("root");
        request.setPassword("secret123");

        User admin = authService.setupRootAdmin(request);

        assertNotNull(admin.getId());
        assertEquals("root", admin.getUsername());
        assertEquals(UserRole.ROOT_ADMIN, admin.getRole());
    }

    @Test
    void setupRootAdmin_whenAlreadyExists_throwsException() {
        SetupRequest request = new SetupRequest();
        request.setUsername("root");
        request.setPassword("secret123");
        authService.setupRootAdmin(request);

        SetupRequest second = new SetupRequest();
        second.setUsername("root2");
        second.setPassword("secret456");

        assertThrows(IllegalStateException.class, () -> authService.setupRootAdmin(second));
    }

    @Test
    void authenticate_withValidCredentials_returnsUser() {
        SetupRequest setup = new SetupRequest();
        setup.setUsername("admin");
        setup.setPassword("password");
        authService.setupRootAdmin(setup);

        LoginRequest login = new LoginRequest();
        login.setUsername("admin");
        login.setPassword("password");

        Optional<User> user = authService.authenticate(login);
        assertTrue(user.isPresent());
        assertEquals("admin", user.get().getUsername());
    }

    @Test
    void authenticate_withInvalidPassword_returnsEmpty() {
        SetupRequest setup = new SetupRequest();
        setup.setUsername("admin");
        setup.setPassword("password");
        authService.setupRootAdmin(setup);

        LoginRequest login = new LoginRequest();
        login.setUsername("admin");
        login.setPassword("wrongpassword");

        Optional<User> user = authService.authenticate(login);
        assertFalse(user.isPresent());
    }

    @Test
    void authenticate_withNonexistentUser_returnsEmpty() {
        LoginRequest login = new LoginRequest();
        login.setUsername("nobody");
        login.setPassword("password");

        Optional<User> user = authService.authenticate(login);
        assertFalse(user.isPresent());
    }

    @Test
    void authenticate_whenUserPendingApproval_throwsPendingException() {
        User pending = new User("pendinguser", passwordEncoder.encode("pass123"), UserRole.USER);
        pending.setApproved(false);
        pending.setDenied(false);
        userRepository.save(pending);

        LoginRequest login = new LoginRequest();
        login.setUsername("pendinguser");
        login.setPassword("pass123");

        assertThrows(AccountPendingApprovalException.class, () -> authService.authenticate(login));
    }

    @Test
    void authenticate_whenUserDenied_throwsDeniedException() {
        User denied = new User("denieduser", passwordEncoder.encode("pass123"), UserRole.USER);
        denied.setApproved(false);
        denied.setDenied(true);
        userRepository.save(denied);

        LoginRequest login = new LoginRequest();
        login.setUsername("denieduser");
        login.setPassword("pass123");

        assertThrows(AccountDeniedException.class, () -> authService.authenticate(login));
    }

    @Test
    void authenticate_adminUserNotApproved_succeedsWithoutApprovalCheck() {
        // ADMIN users are not subject to the approval flow
        User admin = new User("adminuser", passwordEncoder.encode("pass123"), UserRole.ADMIN);
        userRepository.save(admin);

        LoginRequest login = new LoginRequest();
        login.setUsername("adminuser");
        login.setPassword("pass123");

        Optional<User> result = authService.authenticate(login);
        assertTrue(result.isPresent());
    }

    @Test
    void register_withApprovalNotRequired_createsApprovedUser() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("newuser");
        request.setPassword("password123");

        User user = authService.register(request);

        assertNotNull(user.getId());
        assertEquals("newuser", user.getUsername());
        assertEquals(UserRole.USER, user.getRole());
        assertTrue(user.isApproved());
        assertFalse(user.isDenied());
        assertNotNull(user.getGroup());
        assertEquals("Registered User", user.getGroup().getName());
    }

    @Test
    void register_withApprovalRequired_createsPendingUser() {
        SiteSettings settings = siteSettingsRepository.findAll().stream()
                .findFirst().orElseGet(SiteSettings::new);
        settings.setRequireRegistrationApproval(true);
        siteSettingsRepository.save(settings);

        RegisterRequest request = new RegisterRequest();
        request.setUsername("pendinguser");
        request.setPassword("password123");

        User user = authService.register(request);

        assertFalse(user.isApproved());
        assertFalse(user.isDenied());
        assertEquals("Registered User", user.getGroup().getName());
    }

    @Test
    void register_withDuplicateUsername_throwsException() {
        RegisterRequest first = new RegisterRequest();
        first.setUsername("dupuser");
        first.setPassword("password123");
        authService.register(first);

        RegisterRequest second = new RegisterRequest();
        second.setUsername("dupuser");
        second.setPassword("different123");

        assertThrows(IllegalArgumentException.class, () -> authService.register(second));
    }

    @Test
    void createAdmin_createsNewAdminUser() {
        User admin = authService.createAdmin("newadmin", "pass123");

        assertEquals("newadmin", admin.getUsername());
        assertEquals(UserRole.ADMIN, admin.getRole());
    }

    @Test
    void createAdmin_duplicateUsername_throwsException() {
        authService.createAdmin("admin1", "pass");
        assertThrows(IllegalArgumentException.class, () -> authService.createAdmin("admin1", "pass2"));
    }
}
