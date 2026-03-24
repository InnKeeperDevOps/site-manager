package com.sitemanager.service;

import com.sitemanager.dto.LoginRequest;
import com.sitemanager.dto.SetupRequest;
import com.sitemanager.model.User;
import com.sitemanager.model.enums.UserRole;
import com.sitemanager.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public boolean isSetupRequired() {
        return !userRepository.existsByRole(UserRole.ROOT_ADMIN);
    }

    public User setupRootAdmin(SetupRequest request) {
        if (!isSetupRequired()) {
            throw new IllegalStateException("Root admin already exists");
        }
        User admin = new User(
                request.getUsername(),
                passwordEncoder.encode(request.getPassword()),
                UserRole.ROOT_ADMIN
        );
        return userRepository.save(admin);
    }

    public Optional<User> authenticate(LoginRequest request) {
        return userRepository.findByUsername(request.getUsername())
                .filter(user -> passwordEncoder.matches(request.getPassword(), user.getPasswordHash()));
    }

    public User createAdmin(String username, String password) {
        if (userRepository.findByUsername(username).isPresent()) {
            throw new IllegalArgumentException("Username already taken");
        }
        User admin = new User(username, passwordEncoder.encode(password), UserRole.ADMIN);
        return userRepository.save(admin);
    }

    public Optional<User> findByUsername(String username) {
        return userRepository.findByUsername(username);
    }
}
