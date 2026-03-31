package com.sitemanager.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlanTaskRetryTrackingTest {

    @Test
    void retryCount_defaultsToZero() {
        PlanTask task = new PlanTask();
        assertThat(task.getRetryCount()).isEqualTo(0);
    }

    @Test
    void retryCount_canBeSet() {
        PlanTask task = new PlanTask();
        task.setRetryCount(3);
        assertThat(task.getRetryCount()).isEqualTo(3);
    }

    @Test
    void retryCount_canBeIncremented() {
        PlanTask task = new PlanTask();
        task.setRetryCount(task.getRetryCount() + 1);
        assertThat(task.getRetryCount()).isEqualTo(1);
    }

    @Test
    void failureReason_defaultsToNull() {
        PlanTask task = new PlanTask();
        assertThat(task.getFailureReason()).isNull();
    }

    @Test
    void failureReason_canBeSet() {
        PlanTask task = new PlanTask();
        task.setFailureReason("Claude session timed out");
        assertThat(task.getFailureReason()).isEqualTo("Claude session timed out");
    }

    @Test
    void failureReason_canBeCleared() {
        PlanTask task = new PlanTask();
        task.setFailureReason("some error");
        task.setFailureReason(null);
        assertThat(task.getFailureReason()).isNull();
    }

    @Test
    void retryCountAndFailureReason_areIndependent() {
        PlanTask task = new PlanTask();
        task.setRetryCount(2);
        task.setFailureReason("network error");
        assertThat(task.getRetryCount()).isEqualTo(2);
        assertThat(task.getFailureReason()).isEqualTo("network error");
    }
}
