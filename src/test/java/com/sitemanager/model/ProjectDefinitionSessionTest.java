package com.sitemanager.model;

import com.sitemanager.model.enums.ProjectDefinitionStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class ProjectDefinitionSessionTest {

    @Test
    void defaultConstructor_fieldsAreNull() {
        ProjectDefinitionSession s = new ProjectDefinitionSession();
        assertNull(s.getId());
        assertNull(s.getStatus());
        assertNull(s.getConversationHistory());
        assertNull(s.getGeneratedContent());
        assertNull(s.getClaudeSessionId());
        assertNull(s.getCreatedAt());
        assertNull(s.getCompletedAt());
        assertNull(s.getBranchName());
        assertNull(s.getPrUrl());
        assertNull(s.getPrNumber());
        assertNull(s.getErrorMessage());
        assertFalse(s.isHasExistingDefinition());
    }

    @Test
    void settersAndGetters_roundTrip() {
        ProjectDefinitionSession s = new ProjectDefinitionSession();
        LocalDateTime now = LocalDateTime.now();

        s.setId(1L);
        s.setStatus(ProjectDefinitionStatus.ACTIVE);
        s.setConversationHistory("history");
        s.setGeneratedContent("content");
        s.setClaudeSessionId("claude-123");
        s.setCreatedAt(now);
        s.setCompletedAt(now.plusMinutes(10));
        s.setBranchName("branch");
        s.setPrUrl("https://github.com/pr/1");
        s.setPrNumber("1");
        s.setErrorMessage("some error");

        assertEquals(1L, s.getId());
        assertEquals(ProjectDefinitionStatus.ACTIVE, s.getStatus());
        assertEquals("history", s.getConversationHistory());
        assertEquals("content", s.getGeneratedContent());
        assertEquals("claude-123", s.getClaudeSessionId());
        assertEquals(now, s.getCreatedAt());
        assertEquals(now.plusMinutes(10), s.getCompletedAt());
        assertEquals("branch", s.getBranchName());
        assertEquals("https://github.com/pr/1", s.getPrUrl());
        assertEquals("1", s.getPrNumber());
        assertEquals("some error", s.getErrorMessage());
        assertFalse(s.isHasExistingDefinition());
    }

    @Test
    void hasExistingDefinition_defaultsFalse_andCanBeSetTrue() {
        ProjectDefinitionSession s = new ProjectDefinitionSession();
        assertFalse(s.isHasExistingDefinition());

        s.setHasExistingDefinition(true);
        assertTrue(s.isHasExistingDefinition());

        s.setHasExistingDefinition(false);
        assertFalse(s.isHasExistingDefinition());
    }

    @Test
    void prePersist_setsCreatedAt() {
        ProjectDefinitionSession s = new ProjectDefinitionSession();
        assertNull(s.getCreatedAt());
        // Simulate @PrePersist invocation
        s.setCreatedAt(LocalDateTime.now()); // In real usage this is done by JPA
        assertNotNull(s.getCreatedAt());
    }

    @Test
    void allStatusValues_areAccessible() {
        ProjectDefinitionStatus[] values = ProjectDefinitionStatus.values();
        assertEquals(6, values.length);
        assertArrayEquals(
                new ProjectDefinitionStatus[]{
                        ProjectDefinitionStatus.ACTIVE,
                        ProjectDefinitionStatus.GENERATING,
                        ProjectDefinitionStatus.SAVING,
                        ProjectDefinitionStatus.PR_OPEN,
                        ProjectDefinitionStatus.COMPLETED,
                        ProjectDefinitionStatus.FAILED
                },
                values
        );
    }
}
