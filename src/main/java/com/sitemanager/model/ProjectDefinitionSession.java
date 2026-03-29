package com.sitemanager.model;

import com.sitemanager.model.enums.ProjectDefinitionStatus;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "project_definition_sessions")
public class ProjectDefinitionSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProjectDefinitionStatus status;

    @Column(columnDefinition = "TEXT")
    private String conversationHistory;

    @Column(columnDefinition = "TEXT")
    private String generatedContent;

    private String claudeSessionId;

    private LocalDateTime createdAt;

    private LocalDateTime completedAt;

    private String branchName;

    private String prUrl;

    private String prNumber;

    private String errorMessage;

    @Column(name = "has_existing_definition", nullable = false)
    private boolean hasExistingDefinition = false;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public ProjectDefinitionStatus getStatus() {
        return status;
    }

    public void setStatus(ProjectDefinitionStatus status) {
        this.status = status;
    }

    public String getConversationHistory() {
        return conversationHistory;
    }

    public void setConversationHistory(String conversationHistory) {
        this.conversationHistory = conversationHistory;
    }

    public String getGeneratedContent() {
        return generatedContent;
    }

    public void setGeneratedContent(String generatedContent) {
        this.generatedContent = generatedContent;
    }

    public String getClaudeSessionId() {
        return claudeSessionId;
    }

    public void setClaudeSessionId(String claudeSessionId) {
        this.claudeSessionId = claudeSessionId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public String getBranchName() {
        return branchName;
    }

    public void setBranchName(String branchName) {
        this.branchName = branchName;
    }

    public String getPrUrl() {
        return prUrl;
    }

    public void setPrUrl(String prUrl) {
        this.prUrl = prUrl;
    }

    public String getPrNumber() {
        return prNumber;
    }

    public void setPrNumber(String prNumber) {
        this.prNumber = prNumber;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public boolean isHasExistingDefinition() {
        return hasExistingDefinition;
    }

    public void setHasExistingDefinition(boolean hasExistingDefinition) {
        this.hasExistingDefinition = hasExistingDefinition;
    }
}
