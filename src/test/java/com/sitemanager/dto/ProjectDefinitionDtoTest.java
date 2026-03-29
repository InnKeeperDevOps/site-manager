package com.sitemanager.dto;

import com.sitemanager.model.enums.ProjectDefinitionStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProjectDefinitionDtoTest {

    @Test
    void answerRequest_getAndSet() {
        ProjectDefinitionAnswerRequest req = new ProjectDefinitionAnswerRequest();
        req.setAnswer("My answer");
        assertEquals("My answer", req.getAnswer());
    }

    @Test
    void answerRequest_constructorSetsAnswer() {
        ProjectDefinitionAnswerRequest req = new ProjectDefinitionAnswerRequest("test answer");
        assertEquals("test answer", req.getAnswer());
    }

    @Test
    void answerRequest_defaultConstructor_answerIsNull() {
        ProjectDefinitionAnswerRequest req = new ProjectDefinitionAnswerRequest();
        assertNull(req.getAnswer());
    }

    @Test
    void stateResponse_gettersAndSetters() {
        ProjectDefinitionStateResponse resp = new ProjectDefinitionStateResponse();
        resp.setSessionId(42L);
        resp.setStatus(ProjectDefinitionStatus.ACTIVE);
        resp.setCurrentQuestion("What is the project name?");
        resp.setQuestionType("text");
        resp.setOptions(List.of("Option A", "Option B"));
        resp.setProgressPercent(25);
        resp.setGeneratedContent("Generated doc content");
        resp.setPrUrl("https://github.com/org/repo/pull/1");
        resp.setErrorMessage("Something went wrong");
        resp.setIsEdit(true);

        assertEquals(42L, resp.getSessionId());
        assertEquals(ProjectDefinitionStatus.ACTIVE, resp.getStatus());
        assertEquals("What is the project name?", resp.getCurrentQuestion());
        assertEquals("text", resp.getQuestionType());
        assertEquals(List.of("Option A", "Option B"), resp.getOptions());
        assertEquals(25, resp.getProgressPercent());
        assertEquals("Generated doc content", resp.getGeneratedContent());
        assertEquals("https://github.com/org/repo/pull/1", resp.getPrUrl());
        assertEquals("Something went wrong", resp.getErrorMessage());
        assertTrue(resp.isEdit());
    }

    @Test
    void stateResponse_defaultConstructor_fieldsAreDefault() {
        ProjectDefinitionStateResponse resp = new ProjectDefinitionStateResponse();
        assertNull(resp.getSessionId());
        assertNull(resp.getStatus());
        assertNull(resp.getCurrentQuestion());
        assertNull(resp.getQuestionType());
        assertNull(resp.getOptions());
        assertEquals(0, resp.getProgressPercent());
        assertNull(resp.getGeneratedContent());
        assertNull(resp.getPrUrl());
        assertNull(resp.getErrorMessage());
        assertFalse(resp.isEdit());
    }

    @Test
    void stateResponse_isEdit_defaultsFalse_andCanBeSetTrue() {
        ProjectDefinitionStateResponse resp = new ProjectDefinitionStateResponse();
        assertFalse(resp.isEdit());

        resp.setIsEdit(true);
        assertTrue(resp.isEdit());

        resp.setIsEdit(false);
        assertFalse(resp.isEdit());
    }

    @Test
    void stateResponse_nullOptions() {
        ProjectDefinitionStateResponse resp = new ProjectDefinitionStateResponse();
        resp.setOptions(null);
        assertNull(resp.getOptions());
    }

    @Test
    void stateResponse_progressPercentBoundaries() {
        ProjectDefinitionStateResponse resp = new ProjectDefinitionStateResponse();
        resp.setProgressPercent(0);
        assertEquals(0, resp.getProgressPercent());
        resp.setProgressPercent(100);
        assertEquals(100, resp.getProgressPercent());
    }
}
