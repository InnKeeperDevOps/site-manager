package com.sitemanager.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SuggestionExpertReviewSummaryTest {

    private Suggestion suggestionWithNotes(String notes) {
        Suggestion s = new Suggestion();
        s.setExpertReviewNotes(notes);
        return s;
    }

    @Test
    void returnsEmptyList_whenNotesAreNull() {
        Suggestion s = new Suggestion();
        assertThat(s.getExpertReviewSummary()).isEmpty();
    }

    @Test
    void returnsEmptyList_whenNotesAreBlank() {
        Suggestion s = suggestionWithNotes("   ");
        assertThat(s.getExpertReviewSummary()).isEmpty();
    }

    @Test
    void parsesApprovedEntry_whenNoNegativeKeywords() {
        String notes = "**Software Architect**: The plan looks well-structured and follows good design patterns.";
        List<Suggestion.ExpertReviewEntry> result = suggestionWithNotes(notes).getExpertReviewSummary();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getExpertName()).isEqualTo("Software Architect");
        assertThat(result.get(0).getStatus()).isEqualTo("APPROVED");
        assertThat(result.get(0).getKeyPoint()).isNotBlank();
    }

    @Test
    void parsesFlaggedEntry_whenNoteContainsConcern() {
        String notes = "**Security Engineer**: There is a concern about the authentication flow that needs attention.";
        List<Suggestion.ExpertReviewEntry> result = suggestionWithNotes(notes).getExpertReviewSummary();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo("FLAGGED");
    }

    @Test
    void parsesFlaggedEntry_whenNoteContainsRisk() {
        String notes = "**Infrastructure Engineer**: There is a risk of data loss under high load conditions.";
        List<Suggestion.ExpertReviewEntry> result = suggestionWithNotes(notes).getExpertReviewSummary();

        assertThat(result.get(0).getStatus()).isEqualTo("FLAGGED");
    }

    @Test
    void parsesMultipleEntries() {
        String notes = "**Software Architect**: Overall this looks good and well thought out.\n\n**Security Engineer**: No issue found with the proposed approach.";
        List<Suggestion.ExpertReviewEntry> result = suggestionWithNotes(notes).getExpertReviewSummary();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getExpertName()).isEqualTo("Software Architect");
        assertThat(result.get(1).getExpertName()).isEqualTo("Security Engineer");
    }

    @Test
    void extractsKeyPoint_asFirstSentence() {
        String notes = "**Software Architect**: The design is solid. There are many other observations.";
        List<Suggestion.ExpertReviewEntry> result = suggestionWithNotes(notes).getExpertReviewSummary();

        assertThat(result.get(0).getKeyPoint()).isEqualTo("The design is solid.");
    }

    @Test
    void truncatesKeyPoint_whenNoteIsLong() {
        String longNote = "A".repeat(200);
        String notes = "**Software Architect**: " + longNote;
        List<Suggestion.ExpertReviewEntry> result = suggestionWithNotes(notes).getExpertReviewSummary();

        assertThat(result.get(0).getKeyPoint()).hasSize(153); // 150 + "..."
        assertThat(result.get(0).getKeyPoint()).endsWith("...");
    }

    @Test
    void skipsEntriesWithMalformedFormat() {
        String notes = "Some random text without bold markers\n\n**Valid Expert**: A proper review note.";
        List<Suggestion.ExpertReviewEntry> result = suggestionWithNotes(notes).getExpertReviewSummary();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getExpertName()).isEqualTo("Valid Expert");
    }

    @Test
    void handlesUserGuidanceEntry() {
        String notes = "**Software Architect**: Looks good overall.\n\nUser guidance after review rounds:\nPlease focus on performance.";
        List<Suggestion.ExpertReviewEntry> result = suggestionWithNotes(notes).getExpertReviewSummary();

        // Only the valid expert entry is parsed (user guidance doesn't start with **)
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getExpertName()).isEqualTo("Software Architect");
    }

    @Test
    void flagsEntry_whenNoteContainsVulnerab() {
        String notes = "**Security Engineer**: Found a vulnerab in the input handling.";
        List<Suggestion.ExpertReviewEntry> result = suggestionWithNotes(notes).getExpertReviewSummary();

        assertThat(result.get(0).getStatus()).isEqualTo("FLAGGED");
    }

    @Test
    void parsesProjectOwnerEntry_withApprovedStatus() {
        String notes = "**Project Owner**: The plan fully captures the original request. All key requirements are addressed.";
        List<Suggestion.ExpertReviewEntry> result = suggestionWithNotes(notes).getExpertReviewSummary();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getExpertName()).isEqualTo("Project Owner");
        assertThat(result.get(0).getStatus()).isEqualTo("APPROVED");
        assertThat(result.get(0).getKeyPoint()).isNotBlank();
    }

    @Test
    void parsesProjectOwnerEntry_withFlaggedStatus_whenNoteContainsConcern() {
        String notes = "**Project Owner**: There is a concern that the plan does not cover the full scope of the original request.";
        List<Suggestion.ExpertReviewEntry> result = suggestionWithNotes(notes).getExpertReviewSummary();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getExpertName()).isEqualTo("Project Owner");
        assertThat(result.get(0).getStatus()).isEqualTo("FLAGGED");
    }

    @Test
    void parsesProjectOwnerEntry_alongsideOtherExperts() {
        String notes = "**Project Owner**: The plan is complete and captures everything requested.\n\n" +
                "**Software Architect**: The architecture looks solid and well-designed.";
        List<Suggestion.ExpertReviewEntry> result = suggestionWithNotes(notes).getExpertReviewSummary();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getExpertName()).isEqualTo("Project Owner");
        assertThat(result.get(1).getExpertName()).isEqualTo("Software Architect");
    }

    @Test
    void parsesProjectOwnerEntry_keyPointIsFirstSentence() {
        String notes = "**Project Owner**: The plan is complete. There are additional details about the locked tasks.";
        List<Suggestion.ExpertReviewEntry> result = suggestionWithNotes(notes).getExpertReviewSummary();

        assertThat(result.get(0).getKeyPoint()).isEqualTo("The plan is complete.");
    }
}
