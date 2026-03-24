package com.sitemanager.model;

import jakarta.persistence.*;

@Entity
@Table(name = "site_settings")
public class SiteSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private boolean allowAnonymousSuggestions = true;

    @Column(nullable = false)
    private boolean allowVoting = true;

    @Column
    private String targetRepoUrl;

    @Column(nullable = false)
    private int suggestionTimeoutMinutes = 1440;

    @Column(nullable = false)
    private boolean requireApproval = true;

    @Column
    private String siteName = "Site Suggestion Platform";

    public SiteSettings() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public boolean isAllowAnonymousSuggestions() { return allowAnonymousSuggestions; }
    public void setAllowAnonymousSuggestions(boolean v) { this.allowAnonymousSuggestions = v; }
    public boolean isAllowVoting() { return allowVoting; }
    public void setAllowVoting(boolean v) { this.allowVoting = v; }
    public String getTargetRepoUrl() { return targetRepoUrl; }
    public void setTargetRepoUrl(String v) { this.targetRepoUrl = v; }
    public int getSuggestionTimeoutMinutes() { return suggestionTimeoutMinutes; }
    public void setSuggestionTimeoutMinutes(int v) { this.suggestionTimeoutMinutes = v; }
    public boolean isRequireApproval() { return requireApproval; }
    public void setRequireApproval(boolean v) { this.requireApproval = v; }
    public String getSiteName() { return siteName; }
    public void setSiteName(String v) { this.siteName = v; }
}
