package com.sitemanager.model;

import jakarta.persistence.*;

@Entity
@Table(name = "user_groups")
public class UserGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String name;

    @Column(nullable = false)
    private boolean canCreateSuggestions = false;

    @Column(nullable = false)
    private boolean canVote = false;

    @Column(nullable = false)
    private boolean canReply = false;

    @Column(nullable = false)
    private boolean canApproveDenySuggestions = false;

    @Column(nullable = false)
    private boolean canManageSettings = false;

    @Column(nullable = false)
    private boolean canManageUsers = false;

    public UserGroup() {}

    public UserGroup(String name, boolean canCreateSuggestions, boolean canVote, boolean canReply,
                     boolean canApproveDenySuggestions, boolean canManageSettings, boolean canManageUsers) {
        this.name = name;
        this.canCreateSuggestions = canCreateSuggestions;
        this.canVote = canVote;
        this.canReply = canReply;
        this.canApproveDenySuggestions = canApproveDenySuggestions;
        this.canManageSettings = canManageSettings;
        this.canManageUsers = canManageUsers;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean isCanCreateSuggestions() { return canCreateSuggestions; }
    public void setCanCreateSuggestions(boolean v) { this.canCreateSuggestions = v; }
    public boolean isCanVote() { return canVote; }
    public void setCanVote(boolean v) { this.canVote = v; }
    public boolean isCanReply() { return canReply; }
    public void setCanReply(boolean v) { this.canReply = v; }
    public boolean isCanApproveDenySuggestions() { return canApproveDenySuggestions; }
    public void setCanApproveDenySuggestions(boolean v) { this.canApproveDenySuggestions = v; }
    public boolean isCanManageSettings() { return canManageSettings; }
    public void setCanManageSettings(boolean v) { this.canManageSettings = v; }
    public boolean isCanManageUsers() { return canManageUsers; }
    public void setCanManageUsers(boolean v) { this.canManageUsers = v; }
}
