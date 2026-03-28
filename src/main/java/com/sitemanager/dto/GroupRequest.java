package com.sitemanager.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class GroupRequest {

    @NotBlank(message = "Group name is required")
    @Size(max = 100)
    private String name;

    private boolean canCreateSuggestions;
    private boolean canVote;
    private boolean canReply;
    private boolean canApproveDenySuggestions;
    private boolean canManageSettings;
    private boolean canManageUsers;

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
