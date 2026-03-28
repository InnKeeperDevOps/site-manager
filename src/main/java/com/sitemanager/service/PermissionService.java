package com.sitemanager.service;

import com.sitemanager.model.User;
import com.sitemanager.model.UserGroup;
import com.sitemanager.model.enums.Permission;
import com.sitemanager.repository.UserRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;

@Service
public class PermissionService {

    private final UserRepository userRepository;

    public PermissionService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public boolean hasPermission(HttpSession session, Permission permission) {
        String role = (String) session.getAttribute("role");
        if (role == null) return false;

        if ("ROOT_ADMIN".equals(role) || "ADMIN".equals(role)) return true;

        if ("USER".equals(role)) {
            Long userId = (Long) session.getAttribute("userId");
            if (userId == null) return false;

            User user = userRepository.findById(userId).orElse(null);
            if (user == null || !user.isApproved()) return false;

            UserGroup group = user.getGroup();
            if (group == null) return false;

            return switch (permission) {
                case CREATE_SUGGESTIONS -> group.isCanCreateSuggestions();
                case VOTE -> group.isCanVote();
                case REPLY -> group.isCanReply();
                case APPROVE_DENY_SUGGESTIONS -> group.isCanApproveDenySuggestions();
                case MANAGE_SETTINGS -> group.isCanManageSettings();
                case MANAGE_USERS -> group.isCanManageUsers();
            };
        }

        return false;
    }
}
