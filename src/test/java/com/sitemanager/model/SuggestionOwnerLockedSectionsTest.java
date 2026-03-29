package com.sitemanager.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SuggestionOwnerLockedSectionsTest {

    @Test
    void getOwnerLockedSections_returnsEmpty_whenFieldIsNull() {
        Suggestion s = new Suggestion();
        assertThat(s.getOwnerLockedSections()).isEmpty();
    }

    @Test
    void getOwnerLockedSections_returnsEmpty_whenFieldIsBlank() {
        Suggestion s = new Suggestion();
        s.setOwnerLockedPlanSections("   ");
        assertThat(s.getOwnerLockedSections()).isEmpty();
    }

    @Test
    void getOwnerLockedSections_returnsEmpty_whenFieldIsInvalidJson() {
        Suggestion s = new Suggestion();
        s.setOwnerLockedPlanSections("not-valid-json");
        assertThat(s.getOwnerLockedSections()).isEmpty();
    }

    @Test
    void roundTrip_storesAndRetrievesIndices() {
        Suggestion s = new Suggestion();
        s.setOwnerLockedSections(Arrays.asList(0, 2, 5));

        List<Integer> result = s.getOwnerLockedSections();
        assertThat(result).containsExactly(0, 2, 5);
    }

    @Test
    void setOwnerLockedSections_setsNullWhenListIsNull() {
        Suggestion s = new Suggestion();
        s.setOwnerLockedSections(null);
        assertThat(s.getOwnerLockedPlanSections()).isNull();
    }

    @Test
    void setOwnerLockedSections_setsNullWhenListIsEmpty() {
        Suggestion s = new Suggestion();
        s.setOwnerLockedSections(Collections.emptyList());
        assertThat(s.getOwnerLockedPlanSections()).isNull();
    }

    @Test
    void setOwnerLockedSections_overwritesPreviousValue() {
        Suggestion s = new Suggestion();
        s.setOwnerLockedSections(Arrays.asList(1, 3));
        s.setOwnerLockedSections(Arrays.asList(4, 7, 9));

        assertThat(s.getOwnerLockedSections()).containsExactly(4, 7, 9);
    }

    @Test
    void setOwnerLockedSections_clearsBySettingEmpty() {
        Suggestion s = new Suggestion();
        s.setOwnerLockedSections(Arrays.asList(1, 2));
        s.setOwnerLockedSections(Collections.emptyList());

        assertThat(s.getOwnerLockedSections()).isEmpty();
    }
}
