package com.sitemanager.service;

import com.sitemanager.dto.GroupRequest;
import com.sitemanager.model.UserGroup;
import com.sitemanager.repository.UserGroupRepository;
import com.sitemanager.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class UserGroupService {

    public static final String DEFAULT_GROUP_NAME = "Registered User";

    private final UserGroupRepository userGroupRepository;
    private final UserRepository userRepository;

    public UserGroupService(UserGroupRepository userGroupRepository, UserRepository userRepository) {
        this.userGroupRepository = userGroupRepository;
        this.userRepository = userRepository;
    }

    public List<UserGroup> findAll() {
        return userGroupRepository.findAll();
    }

    public Optional<UserGroup> findById(Long id) {
        return userGroupRepository.findById(id);
    }

    public UserGroup create(GroupRequest request) {
        if (userGroupRepository.findByName(request.getName()).isPresent()) {
            throw new IllegalArgumentException("A group with that name already exists");
        }
        UserGroup group = new UserGroup(
                request.getName(),
                request.isCanCreateSuggestions(),
                request.isCanVote(),
                request.isCanReply(),
                request.isCanApproveDenySuggestions(),
                request.isCanManageSettings(),
                request.isCanManageUsers()
        );
        return userGroupRepository.save(group);
    }

    public UserGroup update(Long id, GroupRequest request) {
        UserGroup group = userGroupRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Group not found"));

        userGroupRepository.findByName(request.getName()).ifPresent(existing -> {
            if (!existing.getId().equals(id)) {
                throw new IllegalArgumentException("A group with that name already exists");
            }
        });

        group.setName(request.getName());
        group.setCanCreateSuggestions(request.isCanCreateSuggestions());
        group.setCanVote(request.isCanVote());
        group.setCanReply(request.isCanReply());
        group.setCanApproveDenySuggestions(request.isCanApproveDenySuggestions());
        group.setCanManageSettings(request.isCanManageSettings());
        group.setCanManageUsers(request.isCanManageUsers());
        return userGroupRepository.save(group);
    }

    public void delete(Long id) {
        UserGroup group = userGroupRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Group not found"));

        if (DEFAULT_GROUP_NAME.equals(group.getName())) {
            throw new IllegalStateException("Cannot delete the default 'Registered User' group");
        }

        if (userRepository.existsByGroup(group)) {
            throw new IllegalStateException("Cannot delete a group that has users assigned to it");
        }

        userGroupRepository.delete(group);
    }
}
