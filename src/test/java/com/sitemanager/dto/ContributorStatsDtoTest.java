package com.sitemanager.dto;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ContributorStatsDtoTest {

    @Test
    void defaultConstructor_numericFieldsDefaultToZero() {
        ContributorStatsDto dto = new ContributorStatsDto();
        assertEquals(0, dto.getTotalSubmissions());
        assertEquals(0, dto.getMergedSuggestions());
        assertEquals(0, dto.getApprovedSuggestions());
        assertEquals(0, dto.getTotalUpvotesReceived());
        assertEquals(0, dto.getScore());
        assertEquals(0, dto.getRank());
    }

    @Test
    void defaultConstructor_stringAndInstantFieldsDefaultToNull() {
        ContributorStatsDto dto = new ContributorStatsDto();
        assertNull(dto.getAuthorId());
        assertNull(dto.getUsername());
        assertNull(dto.getLastActivityAt());
    }

    @Test
    void settersAndGetters_authorId() {
        ContributorStatsDto dto = new ContributorStatsDto();
        dto.setAuthorId("42");
        assertEquals("42", dto.getAuthorId());
    }

    @Test
    void settersAndGetters_username() {
        ContributorStatsDto dto = new ContributorStatsDto();
        dto.setUsername("alice");
        assertEquals("alice", dto.getUsername());
    }

    @Test
    void settersAndGetters_totalSubmissions() {
        ContributorStatsDto dto = new ContributorStatsDto();
        dto.setTotalSubmissions(10);
        assertEquals(10, dto.getTotalSubmissions());
    }

    @Test
    void settersAndGetters_mergedSuggestions() {
        ContributorStatsDto dto = new ContributorStatsDto();
        dto.setMergedSuggestions(3);
        assertEquals(3, dto.getMergedSuggestions());
    }

    @Test
    void settersAndGetters_approvedSuggestions() {
        ContributorStatsDto dto = new ContributorStatsDto();
        dto.setApprovedSuggestions(5);
        assertEquals(5, dto.getApprovedSuggestions());
    }

    @Test
    void settersAndGetters_totalUpvotesReceived() {
        ContributorStatsDto dto = new ContributorStatsDto();
        dto.setTotalUpvotesReceived(20);
        assertEquals(20, dto.getTotalUpvotesReceived());
    }

    @Test
    void settersAndGetters_score() {
        ContributorStatsDto dto = new ContributorStatsDto();
        dto.setScore(55);
        assertEquals(55, dto.getScore());
    }

    @Test
    void settersAndGetters_rank() {
        ContributorStatsDto dto = new ContributorStatsDto();
        dto.setRank(1);
        assertEquals(1, dto.getRank());
    }

    @Test
    void settersAndGetters_lastActivityAt() {
        ContributorStatsDto dto = new ContributorStatsDto();
        Instant now = Instant.now();
        dto.setLastActivityAt(now);
        assertEquals(now, dto.getLastActivityAt());
    }

    @Test
    void populatedDto_allFieldsReturnExpectedValues() {
        ContributorStatsDto dto = new ContributorStatsDto();
        Instant ts = Instant.parse("2025-01-15T10:00:00Z");
        dto.setAuthorId("7");
        dto.setUsername("bob");
        dto.setTotalSubmissions(8);
        dto.setMergedSuggestions(2);
        dto.setApprovedSuggestions(4);
        dto.setTotalUpvotesReceived(30);
        dto.setScore(46);
        dto.setRank(2);
        dto.setLastActivityAt(ts);

        assertEquals("7", dto.getAuthorId());
        assertEquals("bob", dto.getUsername());
        assertEquals(8, dto.getTotalSubmissions());
        assertEquals(2, dto.getMergedSuggestions());
        assertEquals(4, dto.getApprovedSuggestions());
        assertEquals(30, dto.getTotalUpvotesReceived());
        assertEquals(46, dto.getScore());
        assertEquals(2, dto.getRank());
        assertEquals(ts, dto.getLastActivityAt());
    }
}
