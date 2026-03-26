package com.sitemanager.model;

import com.sitemanager.model.enums.SuggestionStatus;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "suggestions")
public class Suggestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SuggestionStatus status = SuggestionStatus.DRAFT;

    @Column
    private Long authorId;

    @Column
    private String authorName;

    @Column(columnDefinition = "TEXT")
    private String planSummary;

    @Column(columnDefinition = "TEXT")
    private String planDisplaySummary;

    @Column
    private String currentPhase;

    @Column
    private String claudeSessionId;

    @Column
    private int upVotes = 0;

    @Column
    private int downVotes = 0;

    @Column
    private String workingDirectory;

    @Column(columnDefinition = "TEXT")
    private String pendingClarificationQuestions;

    @Column
    private String prUrl;

    @Column
    private Integer prNumber;

    @Column(columnDefinition = "TEXT")
    private String changelogEntry;

    @Column
    private Integer expertReviewStep;

    @Column
    private Integer expertReviewRound;

    @Column(columnDefinition = "TEXT")
    private String expertReviewNotes;

    @Column
    private Boolean expertReviewPlanChanged;

    @Column
    private Integer totalExpertReviewRounds;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Column
    private Instant lastActivityAt;

    public Suggestion() {}

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (lastActivityAt == null) lastActivityAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public SuggestionStatus getStatus() { return status; }
    public void setStatus(SuggestionStatus status) { this.status = status; }
    public Long getAuthorId() { return authorId; }
    public void setAuthorId(Long authorId) { this.authorId = authorId; }
    public String getAuthorName() { return authorName; }
    public void setAuthorName(String authorName) { this.authorName = authorName; }
    public String getPlanSummary() { return planSummary; }
    public void setPlanSummary(String planSummary) { this.planSummary = planSummary; }
    public String getPlanDisplaySummary() { return planDisplaySummary; }
    public void setPlanDisplaySummary(String planDisplaySummary) { this.planDisplaySummary = planDisplaySummary; }
    public String getCurrentPhase() { return currentPhase; }
    public void setCurrentPhase(String currentPhase) { this.currentPhase = currentPhase; }
    public String getClaudeSessionId() { return claudeSessionId; }
    public void setClaudeSessionId(String claudeSessionId) { this.claudeSessionId = claudeSessionId; }
    public int getUpVotes() { return upVotes; }
    public void setUpVotes(int upVotes) { this.upVotes = upVotes; }
    public int getDownVotes() { return downVotes; }
    public void setDownVotes(int downVotes) { this.downVotes = downVotes; }
    public String getWorkingDirectory() { return workingDirectory; }
    public void setWorkingDirectory(String workingDirectory) { this.workingDirectory = workingDirectory; }
    public String getPendingClarificationQuestions() { return pendingClarificationQuestions; }
    public void setPendingClarificationQuestions(String pendingClarificationQuestions) { this.pendingClarificationQuestions = pendingClarificationQuestions; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getLastActivityAt() { return lastActivityAt; }
    public void setLastActivityAt(Instant lastActivityAt) { this.lastActivityAt = lastActivityAt; }
    public String getPrUrl() { return prUrl; }
    public void setPrUrl(String prUrl) { this.prUrl = prUrl; }
    public Integer getPrNumber() { return prNumber; }
    public void setPrNumber(Integer prNumber) { this.prNumber = prNumber; }
    public String getChangelogEntry() { return changelogEntry; }
    public void setChangelogEntry(String changelogEntry) { this.changelogEntry = changelogEntry; }
    public Integer getExpertReviewStep() { return expertReviewStep; }
    public void setExpertReviewStep(Integer expertReviewStep) { this.expertReviewStep = expertReviewStep; }
    public Integer getExpertReviewRound() { return expertReviewRound; }
    public void setExpertReviewRound(Integer expertReviewRound) { this.expertReviewRound = expertReviewRound; }
    public String getExpertReviewNotes() { return expertReviewNotes; }
    public void setExpertReviewNotes(String expertReviewNotes) { this.expertReviewNotes = expertReviewNotes; }
    public Boolean getExpertReviewPlanChanged() { return expertReviewPlanChanged; }
    public void setExpertReviewPlanChanged(Boolean expertReviewPlanChanged) { this.expertReviewPlanChanged = expertReviewPlanChanged; }
    public Integer getTotalExpertReviewRounds() { return totalExpertReviewRounds; }
    public void setTotalExpertReviewRounds(Integer totalExpertReviewRounds) { this.totalExpertReviewRounds = totalExpertReviewRounds; }
}
