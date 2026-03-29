package com.sitemanager.model;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SuggestionExpertApprovalTrackerTest {

    @Test
    void getExpertApprovalMap_returnsEmpty_whenFieldIsNull() {
        Suggestion s = new Suggestion();
        assertThat(s.getExpertApprovalMap()).isEmpty();
    }

    @Test
    void getExpertApprovalMap_returnsEmpty_whenFieldIsBlank() {
        Suggestion s = new Suggestion();
        s.setExpertApprovalTracker("   ");
        assertThat(s.getExpertApprovalMap()).isEmpty();
    }

    @Test
    void getExpertApprovalMap_returnsEmpty_whenFieldIsInvalidJson() {
        Suggestion s = new Suggestion();
        s.setExpertApprovalTracker("not-valid-json");
        assertThat(s.getExpertApprovalMap()).isEmpty();
    }

    @Test
    void roundTrip_storesAndRetrievesSingleEntry() {
        Suggestion s = new Suggestion();
        Map<String, Suggestion.ExpertApprovalEntry> map = new HashMap<>();
        map.put("Security Expert", new Suggestion.ExpertApprovalEntry("APPROVED", 1));
        s.setExpertApprovalMap(map);

        Map<String, Suggestion.ExpertApprovalEntry> result = s.getExpertApprovalMap();
        assertThat(result).containsKey("Security Expert");
        assertThat(result.get("Security Expert").getStatus()).isEqualTo("APPROVED");
        assertThat(result.get("Security Expert").getRound()).isEqualTo(1);
    }

    @Test
    void roundTrip_storesAndRetrievesMultipleEntries() {
        Suggestion s = new Suggestion();
        Map<String, Suggestion.ExpertApprovalEntry> map = new HashMap<>();
        map.put("Security Expert", new Suggestion.ExpertApprovalEntry("APPROVED", 2));
        map.put("Performance Expert", new Suggestion.ExpertApprovalEntry("CHANGES_PROPOSED", 1));
        map.put("Architecture Expert", new Suggestion.ExpertApprovalEntry("CHANGES_REJECTED", 1));
        s.setExpertApprovalMap(map);

        Map<String, Suggestion.ExpertApprovalEntry> result = s.getExpertApprovalMap();
        assertThat(result).hasSize(3);
        assertThat(result.get("Security Expert").getStatus()).isEqualTo("APPROVED");
        assertThat(result.get("Performance Expert").getStatus()).isEqualTo("CHANGES_PROPOSED");
        assertThat(result.get("Architecture Expert").getStatus()).isEqualTo("CHANGES_REJECTED");
    }

    @Test
    void setExpertApprovalMap_setsNullWhenMapIsNull() {
        Suggestion s = new Suggestion();
        s.setExpertApprovalMap(null);
        assertThat(s.getExpertApprovalTracker()).isNull();
    }

    @Test
    void setExpertApprovalMap_setsNullWhenMapIsEmpty() {
        Suggestion s = new Suggestion();
        s.setExpertApprovalMap(new HashMap<>());
        assertThat(s.getExpertApprovalTracker()).isNull();
    }

    @Test
    void setExpertApprovalMap_overwritesPreviousValue() {
        Suggestion s = new Suggestion();
        Map<String, Suggestion.ExpertApprovalEntry> first = new HashMap<>();
        first.put("Expert A", new Suggestion.ExpertApprovalEntry("APPROVED", 1));
        s.setExpertApprovalMap(first);

        Map<String, Suggestion.ExpertApprovalEntry> second = new HashMap<>();
        second.put("Expert B", new Suggestion.ExpertApprovalEntry("NEEDS_CLARIFICATION", 2));
        s.setExpertApprovalMap(second);

        Map<String, Suggestion.ExpertApprovalEntry> result = s.getExpertApprovalMap();
        assertThat(result).doesNotContainKey("Expert A");
        assertThat(result).containsKey("Expert B");
        assertThat(result.get("Expert B").getStatus()).isEqualTo("NEEDS_CLARIFICATION");
    }

    @Test
    void expertApprovalEntry_defaultConstructor_allowsSetters() {
        Suggestion.ExpertApprovalEntry entry = new Suggestion.ExpertApprovalEntry();
        entry.setStatus("APPROVED");
        entry.setRound(3);
        assertThat(entry.getStatus()).isEqualTo("APPROVED");
        assertThat(entry.getRound()).isEqualTo(3);
    }

    @Test
    void rawFieldIsValidJson_afterSetting() {
        Suggestion s = new Suggestion();
        Map<String, Suggestion.ExpertApprovalEntry> map = new HashMap<>();
        map.put("UX Expert", new Suggestion.ExpertApprovalEntry("APPROVED", 1));
        s.setExpertApprovalMap(map);

        String raw = s.getExpertApprovalTracker();
        assertThat(raw).isNotNull();
        assertThat(raw).contains("UX Expert");
        assertThat(raw).contains("APPROVED");
    }
}
