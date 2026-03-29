package com.sitemanager.model.enums;

public enum ExpertRole {
    PROJECT_OWNER("Project Owner",
            "You are the Project Owner — the highest-authority reviewer in this process.\n" +
            "Your role is to ensure this implementation plan completely and faithfully captures everything the user requested.\n" +
            "You review BEFORE all technical experts and your decisions take precedence.\n" +
            "Consider:\n" +
            "- Does the plan address every aspect and requirement described in the original suggestion?\n" +
            "- Is anything the user asked for missing, reduced in scope, or misinterpreted?\n" +
            "- Are there implicit requirements or expectations that should be made explicit?\n" +
            "- Does the plan deliver the full value the user was expecting — nothing critical left out?\n" +
            "- Are the tasks clearly scoped so that completing them will fully satisfy the request?\n" +
            "After your review, identify which tasks are essential to fulfilling the user's original request. " +
            "These will be owner-locked, meaning downstream reviewers can suggest how to build them but cannot remove them."),

    SOFTWARE_ARCHITECT("Software Architect",
            "You are a senior Software Architect. Review this plan from an architectural perspective.\n" +
            "Consider:\n" +
            "- Is the overall approach sound and well-structured?\n" +
            "- Are there better ways to organize the work?\n" +
            "- Will this approach scale well and be maintainable?\n" +
            "- Are there any risks or dependencies that should be addressed?\n" +
            "- Is the order of tasks logical?"),

    DATA_ANALYST("Data Analyst",
            "You are a senior Data Analyst. Review this plan from a data perspective.\n" +
            "Consider:\n" +
            "- How will data be stored, accessed, and managed?\n" +
            "- Are there data integrity or consistency concerns?\n" +
            "- Will the changes affect existing data or reporting?\n" +
            "- Are there performance concerns with how data is handled?\n" +
            "- Is there adequate handling of data validation and edge cases?"),

    SOFTWARE_ENGINEER("Software Engineer",
            "You are a senior Software Engineer. Review this plan from an implementation perspective.\n" +
            "Consider:\n" +
            "- Is the plan technically feasible as described?\n" +
            "- Are there edge cases or error scenarios not covered?\n" +
            "- Is the testing strategy adequate?\n" +
            "- Are the time estimates realistic?\n" +
            "- Are there simpler or more robust approaches to any task?"),

    PRODUCT_MANAGER("Product Manager",
            "You are a senior Product Manager. Review this plan from a product and user experience perspective.\n" +
            "Consider:\n" +
            "- Does the plan fully address what the user requested?\n" +
            "- Will the end result be intuitive and user-friendly?\n" +
            "- Are there gaps between what was asked for and what will be delivered?\n" +
            "- Should anything be added or removed to better serve the user's needs?\n" +
            "- Is the scope appropriate — not too much, not too little?"),

    FRONTEND_ENGINEER("Frontend Engineer",
            "You are a senior Frontend Engineer. Review this plan from a user interface and interaction perspective.\n" +
            "Consider:\n" +
            "- Will the user interface changes be visually consistent and polished?\n" +
            "- Is the interaction flow smooth and intuitive?\n" +
            "- Are loading states, error states, and empty states handled?\n" +
            "- Will the changes work well on different screen sizes?\n" +
            "- Are there accessibility concerns to address?"),

    QA_ENGINEER("QA Engineer",
            "You are a senior QA Engineer. Review this plan from a quality assurance and testing perspective.\n" +
            "Consider:\n" +
            "- Are all user-facing scenarios covered by the plan?\n" +
            "- What could go wrong — what are the riskiest parts?\n" +
            "- Are there boundary conditions or unusual inputs that could cause problems?\n" +
            "- Is the testing strategy thorough enough to catch regressions?\n" +
            "- Are there scenarios where features interact in unexpected ways?"),

    SECURITY_ENGINEER("Security Engineer",
            "You are a senior Security Engineer. Review this plan from a security perspective.\n" +
            "Consider:\n" +
            "- Are there authentication or authorization gaps?\n" +
            "- Could any user input be exploited — injection, scripting, or tampering?\n" +
            "- Is sensitive data properly protected at rest and in transit?\n" +
            "- Are there access control issues — can users see or do things they shouldn't?\n" +
            "- Are there any common vulnerability patterns that should be mitigated?"),

    DEVOPS_ENGINEER("DevOps Engineer",
            "You are a senior DevOps Engineer. Review this plan from a deployment and operations perspective.\n" +
            "Consider:\n" +
            "- Will the changes deploy smoothly without breaking existing functionality?\n" +
            "- Are there configuration or environment changes needed?\n" +
            "- Could the changes cause downtime or require a migration?\n" +
            "- Is there adequate logging and monitoring for the new features?\n" +
            "- Are there build, packaging, or dependency concerns?"),

    PERFORMANCE_ENGINEER("Performance Engineer",
            "You are a senior Performance Engineer. Review this plan from a performance and efficiency perspective.\n" +
            "Consider:\n" +
            "- Are there potential bottlenecks or slow operations?\n" +
            "- Will the changes handle high usage without degrading?\n" +
            "- Is caching or optimization needed for any data access patterns?\n" +
            "- Could any operations block the system or cause delays for users?\n" +
            "- Are there resource usage concerns — memory, connections, or storage?"),

    INFRASTRUCTURE_ENGINEER("Infrastructure Engineer",
            "You are a senior Infrastructure Engineer. Review this plan from an infrastructure and reliability perspective.\n" +
            "Consider:\n" +
            "- Will the changes work across different environments and configurations?\n" +
            "- Are there network, storage, or service dependency concerns?\n" +
            "- Is the system resilient to failures — what happens if a component goes down?\n" +
            "- Are there capacity or resource provisioning needs?\n" +
            "- Is the plan compatible with the existing hosting and runtime setup?"),

    UX_EXPERT("UX Expert",
            "You are a senior UX Expert. Review this plan from a user experience and usability perspective.\n" +
            "Consider:\n" +
            "- Is the user journey clear, intuitive, and frictionless?\n" +
            "- Are there cognitive load issues — is the user being asked to think too much?\n" +
            "- Do the interactions follow established UX patterns and conventions users expect?\n" +
            "- Is feedback provided at every step — do users know what happened and what to do next?\n" +
            "- Are edge cases handled gracefully from the user's perspective — errors, empty states, slow loading?\n" +
            "- Is the information hierarchy clear — can users quickly find what matters most?\n" +
            "- Are there accessibility concerns — color contrast, keyboard navigation, screen reader support?");

    private final String displayName;
    private final String reviewPrompt;

    public enum Domain {
        OWNER,
        ARCHITECTURE,
        SECURITY,
        DATA_PERF,
        IMPLEMENTATION,
        FRONTEND_UX,
        PRODUCT_QA
    }

    ExpertRole(String displayName, String reviewPrompt) {
        this.displayName = displayName;
        this.reviewPrompt = reviewPrompt;
    }

    public String getDisplayName() { return displayName; }
    public String getReviewPrompt() { return reviewPrompt; }

    /**
     * Returns the Project Owner's review prompt. Exposed as a named method so
     * callers can explicitly request the highest-authority review prompt.
     */
    public static String projectOwnerPrompt() {
        return PROJECT_OWNER.getReviewPrompt();
    }

    public Domain domain() {
        return switch (this) {
            case PROJECT_OWNER -> Domain.OWNER;
            case SOFTWARE_ARCHITECT, INFRASTRUCTURE_ENGINEER -> Domain.ARCHITECTURE;
            case SECURITY_ENGINEER -> Domain.SECURITY;
            case DATA_ANALYST, PERFORMANCE_ENGINEER -> Domain.DATA_PERF;
            case SOFTWARE_ENGINEER, DEVOPS_ENGINEER -> Domain.IMPLEMENTATION;
            case FRONTEND_ENGINEER, UX_EXPERT -> Domain.FRONTEND_UX;
            case PRODUCT_MANAGER, QA_ENGINEER -> Domain.PRODUCT_QA;
        };
    }

    /**
     * Returns domains that should re-review when the given domain proposes changes.
     * Includes the changed domain itself plus closely coupled domains.
     */
    public static java.util.Set<Domain> affectedDomains(Domain changedDomain) {
        return switch (changedDomain) {
            case OWNER -> java.util.Set.of(Domain.values()); // owner changes trigger re-review of all domains
            case ARCHITECTURE -> java.util.Set.of(Domain.ARCHITECTURE, Domain.IMPLEMENTATION);
            case SECURITY -> java.util.Set.of(Domain.SECURITY, Domain.ARCHITECTURE);
            case DATA_PERF -> java.util.Set.of(Domain.DATA_PERF, Domain.IMPLEMENTATION);
            case IMPLEMENTATION -> java.util.Set.of(Domain.IMPLEMENTATION);
            case FRONTEND_UX -> java.util.Set.of(Domain.FRONTEND_UX, Domain.PRODUCT_QA);
            case PRODUCT_QA -> java.util.Set.of(Domain.PRODUCT_QA);
        };
    }

    public static ExpertRole[] reviewOrder() {
        return new ExpertRole[] { PROJECT_OWNER, SOFTWARE_ARCHITECT, SECURITY_ENGINEER, INFRASTRUCTURE_ENGINEER,
                DATA_ANALYST, PERFORMANCE_ENGINEER, DEVOPS_ENGINEER,
                SOFTWARE_ENGINEER, FRONTEND_ENGINEER, UX_EXPERT, PRODUCT_MANAGER, QA_ENGINEER };
    }

    /**
     * Expert review batches. Each expert runs as its own batch (sequentially,
     * one after the other) so every expert can see all prior notes.
     */
    public static ExpertRole[][] reviewBatches() {
        ExpertRole[] order = reviewOrder();
        ExpertRole[][] batches = new ExpertRole[order.length][1];
        for (int i = 0; i < order.length; i++) {
            batches[i] = new ExpertRole[] { order[i] };
        }
        return batches;
    }

    /**
     * Get the batch index and position within that batch for a given step.
     * Returns null if step is out of range.
     */
    public static int[] batchForStep(int step) {
        ExpertRole[][] batches = reviewBatches();
        int cumulative = 0;
        for (int b = 0; b < batches.length; b++) {
            if (step < cumulative + batches[b].length) {
                return new int[] { b, step - cumulative };
            }
            cumulative += batches[b].length;
        }
        return null;
    }

    /**
     * Get the starting step index for a given batch.
     */
    public static int batchStartStep(int batchIndex) {
        ExpertRole[][] batches = reviewBatches();
        int step = 0;
        for (int b = 0; b < batchIndex && b < batches.length; b++) {
            step += batches[b].length;
        }
        return step;
    }

    public static ExpertRole fromStep(int step) {
        ExpertRole[] order = reviewOrder();
        if (step < 0 || step >= order.length) return null;
        return order[step];
    }
}
