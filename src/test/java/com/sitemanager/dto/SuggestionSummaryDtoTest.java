package com.sitemanager.dto;

import com.sitemanager.model.Suggestion;
import com.sitemanager.model.enums.SuggestionStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class SuggestionSummaryDtoTest {

    @Test
    void defaultConstructor_fieldsDefaultToNull() {
        SuggestionSummaryDto dto = new SuggestionSummaryDto();
        assertNull(dto.getId());
        assertNull(dto.getTitle());
        assertNull(dto.getStatus());
        assertNull(dto.getCreatedAt());
        assertEquals(0, dto.getUpVotes());
        assertNull(dto.getPrUrl());
    }

    @Test
    void settersAndGetters_id() {
        SuggestionSummaryDto dto = new SuggestionSummaryDto();
        dto.setId(99L);
        assertEquals(99L, dto.getId());
    }

    @Test
    void settersAndGetters_title() {
        SuggestionSummaryDto dto = new SuggestionSummaryDto();
        dto.setTitle("Add dark mode");
        assertEquals("Add dark mode", dto.getTitle());
    }

    @Test
    void settersAndGetters_status() {
        SuggestionSummaryDto dto = new SuggestionSummaryDto();
        dto.setStatus(SuggestionStatus.MERGED);
        assertEquals(SuggestionStatus.MERGED, dto.getStatus());
    }

    @Test
    void settersAndGetters_createdAt() {
        SuggestionSummaryDto dto = new SuggestionSummaryDto();
        Instant ts = Instant.parse("2025-06-01T08:00:00Z");
        dto.setCreatedAt(ts);
        assertEquals(ts, dto.getCreatedAt());
    }

    @Test
    void settersAndGetters_upVotes() {
        SuggestionSummaryDto dto = new SuggestionSummaryDto();
        dto.setUpVotes(12);
        assertEquals(12, dto.getUpVotes());
    }

    @Test
    void settersAndGetters_prUrl() {
        SuggestionSummaryDto dto = new SuggestionSummaryDto();
        dto.setPrUrl("https://github.com/org/repo/pull/42");
        assertEquals("https://github.com/org/repo/pull/42", dto.getPrUrl());
    }

    @Test
    void from_mapsAllFieldsFromSuggestion() {
        Suggestion suggestion = new Suggestion();
        suggestion.setId(5L);
        suggestion.setTitle("Improve search");
        suggestion.setStatus(SuggestionStatus.APPROVED);
        Instant ts = Instant.parse("2025-03-10T12:00:00Z");
        suggestion.setCreatedAt(ts);
        suggestion.setUpVotes(7);
        suggestion.setPrUrl("https://github.com/org/repo/pull/10");

        SuggestionSummaryDto dto = SuggestionSummaryDto.from(suggestion);

        assertEquals(5L, dto.getId());
        assertEquals("Improve search", dto.getTitle());
        assertEquals(SuggestionStatus.APPROVED, dto.getStatus());
        assertEquals(ts, dto.getCreatedAt());
        assertEquals(7, dto.getUpVotes());
        assertEquals("https://github.com/org/repo/pull/10", dto.getPrUrl());
    }

    @Test
    void from_nullPrUrl_mapsAsNull() {
        Suggestion suggestion = new Suggestion();
        suggestion.setId(1L);
        suggestion.setTitle("Fix bug");
        suggestion.setStatus(SuggestionStatus.DISCUSSING);
        suggestion.setCreatedAt(Instant.now());
        suggestion.setUpVotes(0);
        suggestion.setPrUrl(null);

        SuggestionSummaryDto dto = SuggestionSummaryDto.from(suggestion);

        assertNull(dto.getPrUrl());
    }

    @Test
    void from_allSuggestionStatuses_mappedCorrectly() {
        for (SuggestionStatus s : SuggestionStatus.values()) {
            Suggestion suggestion = new Suggestion();
            suggestion.setId(1L);
            suggestion.setTitle("Test");
            suggestion.setStatus(s);
            suggestion.setCreatedAt(Instant.now());

            SuggestionSummaryDto dto = SuggestionSummaryDto.from(suggestion);
            assertEquals(s, dto.getStatus());
        }
    }
}
