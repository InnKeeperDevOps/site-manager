package com.sitemanager.dto;

import com.sitemanager.model.enums.Priority;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DraftDtoTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    // --- SuggestionRequest isDraft tests ---

    @Test
    void suggestionRequest_isDraft_defaultsFalse() {
        SuggestionRequest req = new SuggestionRequest();
        assertFalse(req.isDraft());
    }

    @Test
    void suggestionRequest_isDraft_setAndGet() {
        SuggestionRequest req = new SuggestionRequest();
        req.setIsDraft(true);
        assertTrue(req.isDraft());

        req.setIsDraft(false);
        assertFalse(req.isDraft());
    }

    // --- UpdateDraftRequest tests ---

    @Test
    void updateDraftRequest_gettersAndSetters() {
        UpdateDraftRequest req = new UpdateDraftRequest();
        req.setTitle("Draft Title");
        req.setDescription("Draft description");
        req.setPriority(Priority.HIGH);

        assertEquals("Draft Title", req.getTitle());
        assertEquals("Draft description", req.getDescription());
        assertEquals(Priority.HIGH, req.getPriority());
    }

    @Test
    void updateDraftRequest_descriptionAndPriorityCanBeNull() {
        UpdateDraftRequest req = new UpdateDraftRequest();
        req.setTitle("Title");

        assertNull(req.getDescription());
        assertNull(req.getPriority());

        Set<ConstraintViolation<UpdateDraftRequest>> violations = validator.validate(req);
        assertTrue(violations.isEmpty());
    }

    @Test
    void updateDraftRequest_titleIsRequired() {
        UpdateDraftRequest req = new UpdateDraftRequest();
        req.setDescription("Some description");

        Set<ConstraintViolation<UpdateDraftRequest>> violations = validator.validate(req);
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("title")));
    }

    @Test
    void updateDraftRequest_titleMaxLength() {
        UpdateDraftRequest req = new UpdateDraftRequest();
        req.setTitle("a".repeat(201));

        Set<ConstraintViolation<UpdateDraftRequest>> violations = validator.validate(req);
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("title")));
    }

    @Test
    void updateDraftRequest_titleAtMaxLength_isValid() {
        UpdateDraftRequest req = new UpdateDraftRequest();
        req.setTitle("a".repeat(200));

        Set<ConstraintViolation<UpdateDraftRequest>> violations = validator.validate(req);
        assertTrue(violations.isEmpty());
    }

    @Test
    void updateDraftRequest_descriptionMaxLength() {
        UpdateDraftRequest req = new UpdateDraftRequest();
        req.setTitle("Valid title");
        req.setDescription("a".repeat(5001));

        Set<ConstraintViolation<UpdateDraftRequest>> violations = validator.validate(req);
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("description")));
    }

    @Test
    void updateDraftRequest_descriptionAtMaxLength_isValid() {
        UpdateDraftRequest req = new UpdateDraftRequest();
        req.setTitle("Valid title");
        req.setDescription("a".repeat(5000));

        Set<ConstraintViolation<UpdateDraftRequest>> violations = validator.validate(req);
        assertTrue(violations.isEmpty());
    }
}
