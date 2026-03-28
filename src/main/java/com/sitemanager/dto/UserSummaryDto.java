package com.sitemanager.dto;

import com.sitemanager.model.User;

import java.time.Instant;

public class UserSummaryDto {

    private Long id;
    private String username;
    private String role;
    private Long groupId;
    private String groupName;
    private boolean approved;
    private boolean denied;
    private Instant createdAt;

    public static UserSummaryDto from(User user) {
        UserSummaryDto dto = new UserSummaryDto();
        dto.id = user.getId();
        dto.username = user.getUsername();
        dto.role = user.getRole() != null ? user.getRole().name() : null;
        dto.groupId = user.getGroup() != null ? user.getGroup().getId() : null;
        dto.groupName = user.getGroup() != null ? user.getGroup().getName() : null;
        dto.approved = user.isApproved();
        dto.denied = user.isDenied();
        dto.createdAt = user.getCreatedAt();
        return dto;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public Long getGroupId() { return groupId; }
    public void setGroupId(Long groupId) { this.groupId = groupId; }
    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }
    public boolean isApproved() { return approved; }
    public void setApproved(boolean approved) { this.approved = approved; }
    public boolean isDenied() { return denied; }
    public void setDenied(boolean denied) { this.denied = denied; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
