package com.sitemanager.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "votes", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"suggestionId", "voterIdentifier"})
})
public class Vote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long suggestionId;

    @Column(nullable = false)
    private String voterIdentifier;

    @Column(name = "vote_value", nullable = false)
    private int value; // +1 or -1

    @Column(nullable = false)
    private Instant createdAt;

    public Vote() {}

    public Vote(Long suggestionId, String voterIdentifier, int value) {
        this.suggestionId = suggestionId;
        this.voterIdentifier = voterIdentifier;
        this.value = value;
        this.createdAt = Instant.now();
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSuggestionId() { return suggestionId; }
    public void setSuggestionId(Long suggestionId) { this.suggestionId = suggestionId; }
    public String getVoterIdentifier() { return voterIdentifier; }
    public void setVoterIdentifier(String voterIdentifier) { this.voterIdentifier = voterIdentifier; }
    public int getValue() { return value; }
    public void setValue(int value) { this.value = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
