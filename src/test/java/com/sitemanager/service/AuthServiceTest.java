package com.sitemanager.service;

import com.sitemanager.dto.LoginRequest;
import com.sitemanager.dto.SetupRequest;
import com.sitemanager.model.User;
import com.sitemanager.model.enums.UserRole;
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
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
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
