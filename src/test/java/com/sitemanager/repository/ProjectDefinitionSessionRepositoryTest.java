package com.sitemanager.repository;

import com.sitemanager.model.ProjectDefinitionSession;
import com.sitemanager.model.enums.ProjectDefinitionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ProjectDefinitionSessionRepositoryTest {

    @Autowired
    private ProjectDefinitionSessionRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    private ProjectDefinitionSession saved(ProjectDefinitionStatus status) {
        ProjectDefinitionSession s = new ProjectDefinitionSession();
        s.setStatus(status);
        return repository.save(s);
    }

    @Test
    void prePersist_setsCreatedAt() {
        ProjectDefinitionSession s = new ProjectDefinitionSession();
        s.setStatus(ProjectDefinitionStatus.ACTIVE);
        ProjectDefinitionSession saved = repository.save(s);
        assertNotNull(saved.getCreatedAt());
    }

    @Test
    void findFirstByStatusIn_returnsMatchingSession() {
        saved(ProjectDefinitionStatus.COMPLETED);
        saved(ProjectDefinitionStatus.ACTIVE);

        Optional<ProjectDefinitionSession> result = repository.findFirstByStatusIn(
                List.of(ProjectDefinitionStatus.ACTIVE, ProjectDefinitionStatus.GENERATING));

        assertTrue(result.isPresent());
        assertEquals(ProjectDefinitionStatus.ACTIVE, result.get().getStatus());
    }

    @Test
    void findFirstByStatusIn_returnsEmptyWhenNoMatch() {
        saved(ProjectDefinitionStatus.COMPLETED);

        Optional<ProjectDefinitionSession> result = repository.findFirstByStatusIn(
                List.of(ProjectDefinitionStatus.ACTIVE, ProjectDefinitionStatus.GENERATING));

        assertFalse(result.isPresent());
    }

    @Test
    void findTopByOrderByCreatedAtDesc_returnsMostRecent() throws InterruptedException {
        ProjectDefinitionSession first = new ProjectDefinitionSession();
        first.setStatus(ProjectDefinitionStatus.COMPLETED);
        first.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        repository.save(first);

        ProjectDefinitionSession second = new ProjectDefinitionSession();
        second.setStatus(ProjectDefinitionStatus.FAILED);
        second.setCreatedAt(LocalDateTime.now());
        repository.save(second);

        Optional<ProjectDefinitionSession> result = repository.findTopByOrderByCreatedAtDesc();

        assertTrue(result.isPresent());
        assertEquals(ProjectDefinitionStatus.FAILED, result.get().getStatus());
    }

    @Test
    void findTopByOrderByCreatedAtDesc_returnsEmptyWhenNoSessions() {
        Optional<ProjectDefinitionSession> result = repository.findTopByOrderByCreatedAtDesc();
        assertFalse(result.isPresent());
    }

    @Test
    void save_persistsAllFields() {
        ProjectDefinitionSession s = new ProjectDefinitionSession();
        s.setStatus(ProjectDefinitionStatus.PR_OPEN);
        s.setConversationHistory("[{\"role\":\"user\",\"content\":\"hello\"}]");
        s.setGeneratedContent("# Project Definition\nSome content");
        s.setClaudeSessionId("session-abc-123");
        s.setBranchName("project-definition/2026-03-29");
        s.setPrUrl("https://github.com/org/repo/pull/42");
        s.setPrNumber("42");
        s.setErrorMessage(null);

        ProjectDefinitionSession saved = repository.save(s);
        ProjectDefinitionSession loaded = repository.findById(saved.getId()).orElseThrow();

        assertEquals(ProjectDefinitionStatus.PR_OPEN, loaded.getStatus());
        assertEquals("[{\"role\":\"user\",\"content\":\"hello\"}]", loaded.getConversationHistory());
        assertEquals("# Project Definition\nSome content", loaded.getGeneratedContent());
        assertEquals("session-abc-123", loaded.getClaudeSessionId());
        assertEquals("project-definition/2026-03-29", loaded.getBranchName());
        assertEquals("https://github.com/org/repo/pull/42", loaded.getPrUrl());
        assertEquals("42", loaded.getPrNumber());
        assertNull(loaded.getErrorMessage());
    }

    @Test
    void findFirstByStatusIn_withAllActiveStatuses_detectsNonTerminalSession() {
        saved(ProjectDefinitionStatus.SAVING);

        Optional<ProjectDefinitionSession> result = repository.findFirstByStatusIn(
                List.of(ProjectDefinitionStatus.ACTIVE, ProjectDefinitionStatus.GENERATING,
                        ProjectDefinitionStatus.SAVING, ProjectDefinitionStatus.PR_OPEN));

        assertTrue(result.isPresent());
        assertEquals(ProjectDefinitionStatus.SAVING, result.get().getStatus());
    }
}
