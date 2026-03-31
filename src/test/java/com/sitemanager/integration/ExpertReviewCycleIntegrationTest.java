package com.sitemanager.integration;

import com.sitemanager.model.PlanTask;
import com.sitemanager.model.Suggestion;
import com.sitemanager.model.SuggestionMessage;
import com.sitemanager.model.enums.ExpertRole;
import com.sitemanager.model.enums.SenderType;
import com.sitemanager.model.enums.SuggestionStatus;
import com.sitemanager.model.enums.TaskStatus;
import com.sitemanager.service.SuggestionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ExpertReviewCycleIntegrationTest extends SuggestionLifecycleIntegrationTest {

    @Autowired
    private SuggestionService suggestionService;

    // Expert-approved JSON response (analysis must exceed MIN_EXPERT_ANALYSIS_LENGTH = 50 chars)
    private static final String EXPERT_APPROVED_JSON =
            "{\"status\":\"APPROVED\","
            + "\"analysis\":\"The plan is comprehensive, well-structured, and addresses all requirements thoroughly.\","
            + "\"message\":\"This looks good and is ready to proceed.\"}";

    // CHANGES_PROPOSED JSON for PROJECT_OWNER — handled directly by applyProjectOwnerChanges
    // (no reviewExpertFeedback call needed). Analysis > 50 chars required.
    private static final String PROJECT_OWNER_CHANGES_PROPOSED_JSON =
            "{\"status\":\"CHANGES_PROPOSED\","
            + "\"analysis\":\"The plan needs more detail about error handling and edge cases to fully address the user requirements.\","
            + "\"message\":\"I have adjusted the plan to ensure complete coverage of all requirements.\","
            + "\"revisedPlan\":\"Updated plan: Implement X by doing Y with comprehensive error handling\","
            + "\"revisedTasks\":["
            + "{\"title\":\"Task 1\",\"description\":\"First step with error handling\",\"estimatedMinutes\":60},"
            + "{\"title\":\"Task 2\",\"description\":\"Second step with validation\",\"estimatedMinutes\":60},"
            + "{\"title\":\"Task 3\",\"description\":\"Third step with testing\",\"estimatedMinutes\":60}"
            + "],"
            + "\"lockedTaskIndices\":[]}";

    /**
     * Creates 3 pending plan tasks for the given suggestion, sets planSummary,
     * and advances status to PLANNED so forceReApproval can trigger the expert review pipeline.
     */
    private Suggestion advanceToExpertReview(long id) {
        planTaskRepository.deleteBySuggestionId(id);

        for (int i = 0; i < 3; i++) {
            PlanTask task = new PlanTask();
            task.setSuggestionId(id);
            task.setTaskOrder(i);
            task.setTitle("Task " + (i + 1));
            task.setDescription("Description for task " + (i + 1));
            task.setStatus(TaskStatus.PENDING);
            planTaskRepository.save(task);
        }

        Suggestion suggestion = suggestionRepository.findById(id).orElseThrow();
        suggestion.setPlanSummary("Implement X by doing Y");
        suggestion.setCurrentPhase("expert_review");
        suggestionRepository.save(suggestion);

        // Advance to PLANNED — forceReApproval requires PLANNED or APPROVED status
        advanceTo(id, SuggestionStatus.PLANNED);

        return suggestionRepository.findById(id).orElseThrow();
    }

    /**
     * Stubs all expertReview calls to return APPROVED synchronously.
     * Using completedFuture (synchronous) ensures all experts run within the
     * same @Transactional context, avoiding inter-transaction race conditions
     * on expertReviewPlanChanged that arise with supplyAsync in fast test mocks.
     */
    private void stubAllExpertsApprove() {
        when(claudeService.expertReview(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(CompletableFuture.completedFuture(EXPERT_APPROVED_JSON));
    }

    /**
     * Creates a test suggestion directly in the database and returns its id.
     */
    private long createTestSuggestion() {
        Suggestion suggestion = new Suggestion();
        suggestion.setTitle("Test suggestion for expert review");
        suggestion.setDescription("A detailed description for testing expert review cycles");
        suggestion.setAuthorName(regularUser.getUsername());
        suggestion.setAuthorId(regularUser.getId());
        suggestion.setStatus(SuggestionStatus.DRAFT);
        return suggestionRepository.save(suggestion).getId();
    }

    @Test
    void expertProposesChanges_triggersReReviewRound_eventuallyPlanned() {
        long id = createTestSuggestion();
        advanceToExpertReview(id);

        // PROJECT_OWNER (first in review order) proposes changes only in round 1.
        // This sets expertReviewPlanChanged=true, which triggers a re-review of all experts
        // in round 2 (OWNER domain changes affect all domains). In round 2, PROJECT_OWNER
        // returns APPROVED → no further changes → PLANNED.
        //
        // Using completedFuture (synchronous) avoids a race condition where non-PO experts
        // in async threads might start new DB transactions before PROJECT_OWNER's transaction
        // commits expertReviewPlanChanged=true, causing them to read and re-save the old
        // false value and preventing the re-review round from starting.
        AtomicInteger callCount = new AtomicInteger(0);
        when(claudeService.expertReview(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenAnswer(inv -> {
                    String expertDisplayName = inv.getArgument(1);
                    int reviewRound = inv.getArgument(10);
                    callCount.incrementAndGet();

                    // PROJECT_OWNER proposes changes only in the first round
                    if (ExpertRole.PROJECT_OWNER.getDisplayName().equals(expertDisplayName) && reviewRound == 1) {
                        return CompletableFuture.completedFuture(PROJECT_OWNER_CHANGES_PROPOSED_JSON);
                    }
                    return CompletableFuture.completedFuture(EXPERT_APPROVED_JSON);
                });

        // Trigger the expert review pipeline via the public forceReApproval method
        suggestionService.forceReApproval(id);

        // Wait for status to reach PLANNED — round 1 completes with changes, round 2 re-reviews, then PLANNED
        await().atMost(30, SECONDS).untilAsserted(() -> {
            Suggestion s = suggestionRepository.findById(id).orElseThrow();
            assertThat(s.getStatus()).isEqualTo(SuggestionStatus.PLANNED);
        });

        Suggestion finalSuggestion = suggestionRepository.findById(id).orElseThrow();
        Map<String, Suggestion.ExpertApprovalEntry> approvalMap = finalSuggestion.getExpertApprovalMap();
        assertThat(approvalMap).isNotEmpty();
        approvalMap.values().forEach(entry -> assertThat(entry.getStatus()).isEqualTo("APPROVED"));

        // Verify more than one round occurred:
        // Round 1: 12 experts (PROJECT_OWNER proposes changes, others approve).
        // Round 2: at least 1 re-review before PLANNED (triggered by planChanged=true from round 1).
        assertThat(callCount.get()).isGreaterThan(12);
    }

    @Test
    void maxTotalReviewRoundsReached_suggestionForcedToPlanned() {
        long id = createTestSuggestion();
        advanceToExpertReview(id);

        // PROJECT_OWNER always proposes changes, triggering re-review every round.
        // Round 1: planChanged=true → round 2 starts (totalRounds=2).
        // Round 2: planChanged=true again, but currentRound=2 is not < MAX_EXPERT_REVIEW_ROUNDS=2
        //          → pipeline force-finalizes to PLANNED (max rounds reached).
        AtomicInteger totalCalls = new AtomicInteger(0);
        when(claudeService.expertReview(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenAnswer(inv -> {
                    String expertDisplayName = inv.getArgument(1);
                    totalCalls.incrementAndGet();
                    if (ExpertRole.PROJECT_OWNER.getDisplayName().equals(expertDisplayName)) {
                        return CompletableFuture.completedFuture(PROJECT_OWNER_CHANGES_PROPOSED_JSON);
                    }
                    return CompletableFuture.completedFuture(EXPERT_APPROVED_JSON);
                });

        // Trigger the expert review pipeline
        suggestionService.forceReApproval(id);

        // Wait for forced finalization to PLANNED
        await().atMost(60, SECONDS).untilAsserted(() -> {
            Suggestion s = suggestionRepository.findById(id).orElseThrow();
            assertThat(s.getStatus()).isEqualTo(SuggestionStatus.PLANNED);
        });

        Suggestion finalSuggestion = suggestionRepository.findById(id).orElseThrow();
        assertThat(finalSuggestion.getStatus()).isEqualTo(SuggestionStatus.PLANNED);

        // The pipeline clears totalExpertReviewRounds when transitioning to PLANNED
        assertThat(finalSuggestion.getTotalExpertReviewRounds()).isNull();

        // Verify multiple rounds occurred: round 1 (12 experts) + round 2 re-review (at least 1 expert)
        // gives total > 12 calls, confirming the pipeline ran more than one full round before finalizing
        assertThat(totalCalls.get()).isGreaterThan(12);
    }

    @Test
    void projectOwnerProposesChanges_allExpertsReReview() {
        long id = createTestSuggestion();
        advanceToExpertReview(id);

        // Track call count per expert display name to verify re-review behavior.
        ConcurrentHashMap<String, AtomicInteger> callCountPerExpert = new ConcurrentHashMap<>();

        when(claudeService.expertReview(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenAnswer(inv -> {
                    String expertDisplayName = inv.getArgument(1);
                    int reviewRound = inv.getArgument(10);
                    callCountPerExpert.computeIfAbsent(expertDisplayName, k -> new AtomicInteger(0))
                            .incrementAndGet();

                    // PROJECT_OWNER proposes changes only in round 1; returns APPROVED on re-review
                    if (ExpertRole.PROJECT_OWNER.getDisplayName().equals(expertDisplayName) && reviewRound == 1) {
                        return CompletableFuture.completedFuture(PROJECT_OWNER_CHANGES_PROPOSED_JSON);
                    }
                    return CompletableFuture.completedFuture(EXPERT_APPROVED_JSON);
                });

        suggestionService.forceReApproval(id);

        await().atMost(30, SECONDS).untilAsserted(() -> {
            Suggestion s = suggestionRepository.findById(id).orElseThrow();
            assertThat(s.getStatus()).isEqualTo(SuggestionStatus.PLANNED);
        });

        // PROJECT_OWNER is re-reviewed in round 2: it proposed changes in round 1 (not marked APPROVED
        // in tracker), and its domain (OWNER) is in changedDomains, so ownDomainChanged=true.
        AtomicInteger poCallCount = callCountPerExpert.get(ExpertRole.PROJECT_OWNER.getDisplayName());
        assertThat(poCallCount).as("PROJECT_OWNER must have been called").isNotNull();
        assertThat(poCallCount.get())
                .as("PROJECT_OWNER should be called at least twice (round 1 CHANGES_PROPOSED + round 2 APPROVED)")
                .isGreaterThanOrEqualTo(2);

        // All non-PO experts participated in round 1.
        for (ExpertRole role : ExpertRole.reviewOrder()) {
            if (role != ExpertRole.PROJECT_OWNER) {
                AtomicInteger count = callCountPerExpert.get(role.getDisplayName());
                assertThat(count)
                        .as("Expert %s should have been called in round 1", role.getDisplayName())
                        .isNotNull();
                assertThat(count.get())
                        .as("Expert %s call count", role.getDisplayName())
                        .isGreaterThanOrEqualTo(1);
            }
        }

        // Total: 12 experts in round 1 + PROJECT_OWNER re-reviewed in round 2 = 13 calls minimum.
        int totalCalls = callCountPerExpert.values().stream().mapToInt(AtomicInteger::get).sum();
        assertThat(totalCalls).isGreaterThan(12);
    }

    @Test
    void expertNeedsClarification_pipelinePauses_resumesAfterUserClarification() throws Exception {
        long id = createTestSuggestion();
        advanceToExpertReview(id);

        String clarificationQuestion = "Can you clarify the data model?";
        String needsClarificationJson =
                "{\"status\":\"NEEDS_CLARIFICATION\","
                + "\"analysis\":\"The plan lacks sufficient detail about the data model design and storage requirements.\","
                + "\"questions\":[\"" + clarificationQuestion + "\"],"
                + "\"message\":\"" + clarificationQuestion + "\"}";

        // First expertReview call returns NEEDS_CLARIFICATION; all subsequent calls return APPROVED.
        AtomicInteger expertReviewCallCount = new AtomicInteger(0);
        when(claudeService.expertReview(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenAnswer(inv -> {
                    int callNum = expertReviewCallCount.incrementAndGet();
                    if (callNum == 1) {
                        return CompletableFuture.completedFuture(needsClarificationJson);
                    }
                    return CompletableFuture.completedFuture(EXPERT_APPROVED_JSON);
                });

        // After the user answers, handleExpertClarificationAnswers calls continueConversation.
        // Stub it to return an APPROVED JSON so the paused expert can finish its review.
        when(claudeService.continueConversation(any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(EXPERT_APPROVED_JSON));

        suggestionService.forceReApproval(id);

        // Pipeline pauses: the expert's clarification question is saved as a SenderType.AI message.
        await().atMost(5, SECONDS).untilAsserted(() -> {
            List<SuggestionMessage> messages =
                    suggestionMessageRepository.findBySuggestionIdOrderByCreatedAtAsc(id);
            boolean hasExpertQuestion = messages.stream()
                    .anyMatch(m -> m.getSenderType() == SenderType.AI
                            && m.getContent().contains(clarificationQuestion));
            assertThat(hasExpertQuestion)
                    .as("Expert clarification question should be saved as an AI message")
                    .isTrue();
        });

        // Verify the pipeline paused at EXPERT_REVIEW and did not advance to PLANNED yet.
        Suggestion paused = suggestionRepository.findById(id).orElseThrow();
        assertThat(paused.getStatus()).isEqualTo(SuggestionStatus.EXPERT_REVIEW);

        // User submits the clarification answer via the expert-clarifications endpoint.
        String clarificationBody = "{\"answers\":"
                + "[{\"question\":\"" + clarificationQuestion + "\","
                + "\"answer\":\"The data model uses a relational schema with normalized tables.\"}]}";
        mockMvc.perform(post("/api/suggestions/{id}/expert-clarifications", id)
                        .session(userSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clarificationBody))
                .andExpect(status().isOk());

        // After the answer is submitted the pipeline resumes and eventually reaches PLANNED.
        await().atMost(30, SECONDS).untilAsserted(() -> {
            Suggestion s = suggestionRepository.findById(id).orElseThrow();
            assertThat(s.getStatus()).isEqualTo(SuggestionStatus.PLANNED);
        });
    }
}
