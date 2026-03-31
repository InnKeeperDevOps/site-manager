package com.sitemanager.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SuggestionFailureReasonTest {

    @Test
    void failureReason_defaultsToNull() {
        Suggestion suggestion = new Suggestion();
        assertThat(suggestion.getFailureReason()).isNull();
    }

    @Test
    void failureReason_canBeSet() {
        Suggestion suggestion = new Suggestion();
        suggestion.setFailureReason("Execution failed after 3 retries");
        assertThat(suggestion.getFailureReason()).isEqualTo("Execution failed after 3 retries");
    }

    @Test
    void failureReason_canBeCleared() {
        Suggestion suggestion = new Suggestion();
        suggestion.setFailureReason("some error");
        suggestion.setFailureReason(null);
        assertThat(suggestion.getFailureReason()).isNull();
    }

    @Test
    void failureReason_doesNotAffectOtherFields() {
        Suggestion suggestion = new Suggestion();
        suggestion.setTitle("My Suggestion");
        suggestion.setFailureReason("timed out");
        assertThat(suggestion.getTitle()).isEqualTo("My Suggestion");
        assertThat(suggestion.getFailureReason()).isEqualTo("timed out");
    }
}
