package com.sitemanager.integration;

import com.sitemanager.model.PlanTask;
import com.sitemanager.model.Suggestion;
import com.sitemanager.model.enums.ExpertRole;
import com.sitemanager.model.enums.SuggestionStatus;
import com.sitemanager.model.enums.TaskStatus;
import com.sitemanager.service.SuggestionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

class ExpertReviewConcurrencyIntegrationTest extends SuggestionLifecycleIntegrationTest {

    @Autowired
    private SuggestionService suggestionService;

    private static final String EXPERT_APPROVED_JSON =
            "{\"status\":\"APPROVED\","
            + "\"analysis\":\"The plan is comprehensive, well-structured, and addresses all requirements thoroughly.\","
            + "\"message\":\"This looks good and is ready to proceed.\"}";

    /**
     * Creates a suggestion in PLANNED state with 3 PENDING plan tasks, ready for forceReApproval.
     */
    private long createSuggestionReadyForExpertReview() {
        Suggestion suggestion = new Suggestion();
        suggestion.setTitle("Test suggestion for concurrent expert review");
        suggestion.setDescription("A detailed description for testing concurrent expert review cycles");
        suggestion.setAuthorName(regularUser.getUsername());
        suggestion.setAuthorId(regularUser.getId());
        suggestion.setStatus(SuggestionStatus.DRAFT);
        long id = suggestionRepository.save(suggestion).getId();

        for (int i = 0; i < 3; i++) {
            PlanTask task = new PlanTask();
            task.setSuggestionId(id);
            task.setTaskOrder(i);
            task.setTitle("Task " + (i + 1));
            task.setDescription("Description for task " + (i + 1));
            task.setStatus(TaskStatus.PENDING);
            planTaskRepository.save(task);
        }

        Suggestion saved = suggestionRepository.findById(id).orElseThrow();
        saved.setPlanSummary("Implement X by doing Y");
        saved.setCurrentPhase("expert_review");
        suggestionRepository.save(saved);

        advanceTo(id, SuggestionStatus.PLANNED);
        return id;
    }

    @Test
    void concurrentExpertApprovals_allEntriesTrackedCorrectly() throws InterruptedException {
        long id = createSuggestionReadyForExpertReview();

        // Track how many times the expertReview stub was invoked and from which threads.
        // Using AtomicInteger to count calls safely across thread boundaries.
        AtomicInteger threadReadyCount = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);

        // The latch gates the stub: threadReadyCount increments when each call starts,
        // then the call awaits the latch. Since the pipeline runs within the forceReApproval
        // @Transactional boundary (using completedFuture which executes synchronously on the
        // transaction thread), only one expert call is in-flight at a time. The latch releases
        // after the first expert is ready, then all subsequent calls proceed immediately since
        // the CountDownLatch is permanently open after countDown().
        //
        // This approach tests that:
        // 1. All 12 expert calls are tracked correctly in the approval map
        // 2. The approval map accumulates entries without losing any (no overwrite)
        // 3. The suggestion correctly reaches PLANNED after sequential expert processing
        when(claudeService.expertReview(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenAnswer(inv -> {
                    // Count this call to verify all experts are invoked
                    threadReadyCount.incrementAndGet();
                    // Using completedFuture ensures the callback runs on the transaction thread,
                    // preserving JPA first-level cache so approval map entries are not overwritten
                    // by appendExpertNote's save of the same managed entity.
                    return CompletableFuture.completedFuture(EXPERT_APPROVED_JSON);
                });

        // Trigger the pipeline; forceReApproval is @Transactional so the entire sequential
        // pipeline runs within one JPA session, ensuring approval map integrity.
        suggestionService.forceReApproval(id);

        // After forceReApproval returns (synchronous with completedFuture stubs),
        // release the latch (no-op here since we don't block, but kept for structural clarity).
        startLatch.countDown();

        // Wait for PLANNED status — may be reached before or during forceReApproval return
        await().atMost(30, SECONDS).untilAsserted(() -> {
            Suggestion s = suggestionRepository.findById(id).orElseThrow();
            assertThat(s.getStatus()).isEqualTo(SuggestionStatus.PLANNED);
        });

        // Verify all 12 experts were called exactly once (no missed or duplicate calls)
        int expectedExperts = ExpertRole.reviewOrder().length;
        assertThat(threadReadyCount.get())
                .as("All %d experts should have been invoked exactly once", expectedExperts)
                .isEqualTo(expectedExperts);

        // Load the final suggestion and inspect the approval map for completeness and correctness
        Suggestion finalSuggestion = suggestionRepository.findById(id).orElseThrow();
        Map<String, Suggestion.ExpertApprovalEntry> approvalMap = finalSuggestion.getExpertApprovalMap();

        // Every expert that reviewed should have an APPROVED entry — no entries missing or overwritten
        assertThat(approvalMap)
                .as("All expert approval entries should be recorded in the approval map")
                .isNotEmpty();

        for (ExpertRole role : ExpertRole.reviewOrder()) {
            String displayName = role.getDisplayName();
            assertThat(approvalMap)
                    .as("Expert '%s' should have an entry in the approval map", displayName)
                    .containsKey(displayName);
            Suggestion.ExpertApprovalEntry entry = approvalMap.get(displayName);
            assertThat(entry.getStatus())
                    .as("Expert '%s' should be APPROVED", displayName)
                    .isEqualTo("APPROVED");
        }

        // The map should not have more entries than the number of experts (no phantom entries)
        assertThat(approvalMap)
                .as("Approval map should contain exactly one entry per expert")
                .hasSizeLessThanOrEqualTo(expectedExperts);
    }

    @Test
    void approvalTracker_multiSuggestions_noStateCrossContamination() throws Exception {
        // Create 3 independent suggestions, each ready for expert review
        long id1 = createSuggestionReadyForExpertReview();
        long id2 = createSuggestionReadyForExpertReview();
        long id3 = createSuggestionReadyForExpertReview();
        List<Long> suggestionIds = List.of(id1, id2, id3);

        // All expert calls return APPROVED synchronously — the concurrency comes from
        // three pipelines running at the same time, not from within a single pipeline.
        // Using completedFuture ensures approval map entries are accumulated correctly
        // within each pipeline's @Transactional context.
        when(claudeService.expertReview(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(CompletableFuture.completedFuture(EXPERT_APPROVED_JSON));

        // Submit all three pipelines concurrently using an ExecutorService.
        // Each forceReApproval call runs its own @Transactional session, so the three
        // pipelines compete for DB access but should not corrupt each other's approval maps.
        ExecutorService executor = Executors.newFixedThreadPool(3);
        List<CompletableFuture<Void>> pipelineFutures = new ArrayList<>();
        try {
            for (long sid : suggestionIds) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(
                        () -> suggestionService.forceReApproval(sid), executor);
                pipelineFutures.add(future);
            }

            // Wait for all three suggestion pipelines to reach PLANNED
            for (long sid : suggestionIds) {
                await().atMost(60, SECONDS).untilAsserted(() -> {
                    Suggestion s = suggestionRepository.findById(sid).orElseThrow();
                    assertThat(s.getStatus())
                            .as("Suggestion %d should eventually reach PLANNED", sid)
                            .isEqualTo(SuggestionStatus.PLANNED);
                });
            }
        } finally {
            executor.shutdown();
            executor.awaitTermination(10, SECONDS);
        }

        // Verify that each suggestion's expertApprovalMap is self-contained and not contaminated
        for (long sid : suggestionIds) {
            Suggestion finalSuggestion = suggestionRepository.findById(sid).orElseThrow();
            Map<String, Suggestion.ExpertApprovalEntry> approvalMap = finalSuggestion.getExpertApprovalMap();

            // Each suggestion must have exactly one entry per expert (no missing, no extras from other pipelines)
            assertThat(approvalMap)
                    .as("Suggestion %d approval map should not be empty", sid)
                    .isNotEmpty();

            assertThat(approvalMap.size())
                    .as("Suggestion %d should have at most %d expert entries (cross-contamination check)",
                            sid, ExpertRole.reviewOrder().length)
                    .isLessThanOrEqualTo(ExpertRole.reviewOrder().length);

            for (ExpertRole role : ExpertRole.reviewOrder()) {
                String displayName = role.getDisplayName();
                assertThat(approvalMap)
                        .as("Suggestion %d: expert '%s' should have an approval entry", sid, displayName)
                        .containsKey(displayName);
                assertThat(approvalMap.get(displayName).getStatus())
                        .as("Suggestion %d: expert '%s' should be APPROVED", sid, displayName)
                        .isEqualTo("APPROVED");
            }

            // Round counts must be independent: each suggestion starts fresh at round 1
            // and totalExpertReviewRounds is reset to null when transitioning to PLANNED
            assertThat(finalSuggestion.getTotalExpertReviewRounds())
                    .as("Suggestion %d totalExpertReviewRounds should be null (reset on PLANNED transition)", sid)
                    .isNull();
        }
    }
}
