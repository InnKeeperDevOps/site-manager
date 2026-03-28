package com.sitemanager.dto;

import java.util.List;

public class AuthStatusResponse {

    private boolean setupRequired;
    private boolean loggedIn;
    private String username;
    private String role;
    private List<String> permissions;

    public AuthStatusResponse(boolean setupRequired, boolean loggedIn, String username, String role, List<String> permissions) {
        this.setupRequired = setupRequired;
        this.loggedIn = loggedIn;
        this.username = username;
        this.role = role;
        this.permissions = permissions;
    }

    public boolean isSetupRequired() { return setupRequired; }
    public boolean isLoggedIn() { return loggedIn; }
    public String getUsername() { return username; }
    public String getRole() { return role; }
    public List<String> getPermissions() { return permissions; }
}
