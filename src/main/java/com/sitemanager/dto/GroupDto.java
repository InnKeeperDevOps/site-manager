package com.sitemanager.dto;

import com.sitemanager.model.UserGroup;

public class GroupDto {

    private Long id;
    private String name;
    private boolean canCreateSuggestions;
    private boolean canVote;
    private boolean canReply;
    private boolean canApproveDenySuggestions;
    private boolean canManageSettings;
    private boolean canManageUsers;

    public static GroupDto from(UserGroup group) {
        GroupDto dto = new GroupDto();
        dto.id = group.getId();
        dto.name = group.getName();
        dto.canCreateSuggestions = group.isCanCreateSuggestions();
        dto.canVote = group.isCanVote();
        dto.canReply = group.isCanReply();
        dto.canApproveDenySuggestions = group.isCanApproveDenySuggestions();
        dto.canManageSettings = group.isCanManageSettings();
        dto.canManageUsers = group.isCanManageUsers();
        return dto;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean isCanCreateSuggestions() { return canCreateSuggestions; }
    public void setCanCreateSuggestions(boolean canCreateSuggestions) { this.canCreateSuggestions = canCreateSuggestions; }
    public boolean isCanVote() { return canVote; }
    public void setCanVote(boolean canVote) { this.canVote = canVote; }
    public boolean isCanReply() { return canReply; }
    public void setCanReply(boolean canReply) { this.canReply = canReply; }
    public boolean isCanApproveDenySuggestions() { return canApproveDenySuggestions; }
    public void setCanApproveDenySuggestions(boolean canApproveDenySuggestions) { this.canApproveDenySuggestions = canApproveDenySuggestions; }
    public boolean isCanManageSettings() { return canManageSettings; }
    public void setCanManageSettings(boolean canManageSettings) { this.canManageSettings = canManageSettings; }
    public boolean isCanManageUsers() { return canManageUsers; }
    public void setCanManageUsers(boolean canManageUsers) { this.canManageUsers = canManageUsers; }
}
