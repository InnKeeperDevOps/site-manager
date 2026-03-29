package com.sitemanager.dto;

import com.sitemanager.model.Suggestion;
import com.sitemanager.model.enums.SuggestionStatus;

import java.time.Instant;

public class SuggestionSummaryDto {

    private Long id;
    private String title;
    private SuggestionStatus status;
    private Instant createdAt;
    private int upVotes;
    private String prUrl;

    public SuggestionSummaryDto() {}

    public static SuggestionSummaryDto from(Suggestion suggestion) {
        SuggestionSummaryDto dto = new SuggestionSummaryDto();
        dto.id = suggestion.getId();
        dto.title = suggestion.getTitle();
        dto.status = suggestion.getStatus();
        dto.createdAt = suggestion.getCreatedAt();
        dto.upVotes = suggestion.getUpVotes();
        dto.prUrl = suggestion.getPrUrl();
        return dto;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public SuggestionStatus getStatus() { return status; }
    public void setStatus(SuggestionStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public int getUpVotes() { return upVotes; }
    public void setUpVotes(int upVotes) { this.upVotes = upVotes; }
    public String getPrUrl() { return prUrl; }
    public void setPrUrl(String prUrl) { this.prUrl = prUrl; }
}
