package com.sitemanager.model.enums;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ExpertRoleTest {

    @Test
    void projectOwner_isFirstInReviewOrder() {
        ExpertRole[] order = ExpertRole.reviewOrder();
        assertThat(order[0]).isEqualTo(ExpertRole.PROJECT_OWNER);
    }

    @Test
    void reviewOrder_hasTwelveExperts() {
        assertThat(ExpertRole.reviewOrder()).hasSize(12);
    }

    @Test
    void projectOwner_isAloneInFirstBatch() {
        ExpertRole[][] batches = ExpertRole.reviewBatches();
        assertThat(batches[0]).containsExactly(ExpertRole.PROJECT_OWNER);
    }

    @Test
    void reviewBatches_hasOneBatchPerExpert() {
        assertThat(ExpertRole.reviewBatches().length).isEqualTo(ExpertRole.reviewOrder().length);
    }

    @Test
    void projectOwner_domainIsOwner() {
        assertThat(ExpertRole.PROJECT_OWNER.domain()).isEqualTo(ExpertRole.Domain.OWNER);
    }

    @Test
    void affectedDomains_ownerAffectsAllDomains() {
        Set<ExpertRole.Domain> affected = ExpertRole.affectedDomains(ExpertRole.Domain.OWNER);
        assertThat(affected).containsExactlyInAnyOrder(ExpertRole.Domain.values());
    }

    @Test
    void projectOwnerPrompt_returnsNonBlankPrompt() {
        assertThat(ExpertRole.projectOwnerPrompt()).isNotBlank();
    }

    @Test
    void projectOwnerPrompt_matchesEnumReviewPrompt() {
        assertThat(ExpertRole.projectOwnerPrompt())
                .isEqualTo(ExpertRole.PROJECT_OWNER.getReviewPrompt());
    }

    @Test
    void projectOwnerPrompt_mentionsHighestAuthority() {
        assertThat(ExpertRole.projectOwnerPrompt()).containsIgnoringCase("highest-authority");
    }

    @Test
    void projectOwnerPrompt_mentionsOwnerLock() {
        assertThat(ExpertRole.projectOwnerPrompt()).containsIgnoringCase("owner-locked");
    }

    @Test
    void fromStep_zero_returnsProjectOwner() {
        assertThat(ExpertRole.fromStep(0)).isEqualTo(ExpertRole.PROJECT_OWNER);
    }

    @Test
    void fromStep_one_returnsSoftwareArchitect() {
        assertThat(ExpertRole.fromStep(1)).isEqualTo(ExpertRole.SOFTWARE_ARCHITECT);
    }

    @Test
    void batchForStep_zero_returnsBatchZeroPositionZero() {
        int[] result = ExpertRole.batchForStep(0);
        assertThat(result).isNotNull();
        assertThat(result[0]).isEqualTo(0); // batch index
        assertThat(result[1]).isEqualTo(0); // position within batch
    }

    @Test
    void batchForStep_one_returnsBatchOnePositionZero() {
        int[] result = ExpertRole.batchForStep(1);
        assertThat(result).isNotNull();
        assertThat(result[0]).isEqualTo(1); // batch index (Architecture batch)
        assertThat(result[1]).isEqualTo(0);
    }

    @Test
    void batchStartStep_batchZero_isStepZero() {
        assertThat(ExpertRole.batchStartStep(0)).isEqualTo(0);
    }

    @Test
    void batchStartStep_batchOne_isStepOne() {
        // After solo PROJECT_OWNER at step 0, architecture batch starts at step 1
        assertThat(ExpertRole.batchStartStep(1)).isEqualTo(1);
    }

    @Test
    void projectOwner_displayNameIsProjectOwner() {
        assertThat(ExpertRole.PROJECT_OWNER.getDisplayName()).isEqualTo("Project Owner");
    }

    @Test
    void domain_ownerEnum_exists() {
        assertThat(ExpertRole.Domain.OWNER).isNotNull();
    }

    @Test
    void allExistingExperts_stillPresent() {
        ExpertRole[] order = ExpertRole.reviewOrder();
        assertThat(order).contains(
                ExpertRole.SOFTWARE_ARCHITECT,
                ExpertRole.DATA_ANALYST,
                ExpertRole.SOFTWARE_ENGINEER,
                ExpertRole.PRODUCT_MANAGER,
                ExpertRole.FRONTEND_ENGINEER,
                ExpertRole.QA_ENGINEER,
                ExpertRole.SECURITY_ENGINEER,
                ExpertRole.DEVOPS_ENGINEER,
                ExpertRole.PERFORMANCE_ENGINEER,
                ExpertRole.INFRASTRUCTURE_ENGINEER,
                ExpertRole.UX_EXPERT
        );
    }
}
