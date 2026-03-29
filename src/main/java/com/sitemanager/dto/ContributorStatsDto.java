package com.sitemanager.dto;

import java.time.Instant;

public class ContributorStatsDto {

    private String authorId;
    private String username;
    private int totalSubmissions;
    private int mergedSuggestions;
    private int approvedSuggestions;
    private int totalUpvotesReceived;
    private int score;
    private int rank;
    private Instant lastActivityAt;

    public ContributorStatsDto() {}

    public String getAuthorId() { return authorId; }
    public void setAuthorId(String authorId) { this.authorId = authorId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public int getTotalSubmissions() { return totalSubmissions; }
    public void setTotalSubmissions(int totalSubmissions) { this.totalSubmissions = totalSubmissions; }
    public int getMergedSuggestions() { return mergedSuggestions; }
    public void setMergedSuggestions(int mergedSuggestions) { this.mergedSuggestions = mergedSuggestions; }
    public int getApprovedSuggestions() { return approvedSuggestions; }
    public void setApprovedSuggestions(int approvedSuggestions) { this.approvedSuggestions = approvedSuggestions; }
    public int getTotalUpvotesReceived() { return totalUpvotesReceived; }
    public void setTotalUpvotesReceived(int totalUpvotesReceived) { this.totalUpvotesReceived = totalUpvotesReceived; }
    public int getScore() { return score; }
    public void setScore(int score) { this.score = score; }
    public int getRank() { return rank; }
    public void setRank(int rank) { this.rank = rank; }
    public Instant getLastActivityAt() { return lastActivityAt; }
    public void setLastActivityAt(Instant lastActivityAt) { this.lastActivityAt = lastActivityAt; }
}
