package com.sitemanager.model.enums;

public enum ExpertRole {
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
            "- Are there scenarios where features interact in unexpected ways?");

    private final String displayName;
    private final String reviewPrompt;

    ExpertRole(String displayName, String reviewPrompt) {
        this.displayName = displayName;
        this.reviewPrompt = reviewPrompt;
    }

    public String getDisplayName() { return displayName; }
    public String getReviewPrompt() { return reviewPrompt; }

    public static ExpertRole[] reviewOrder() {
        return new ExpertRole[] { SOFTWARE_ARCHITECT, DATA_ANALYST, SOFTWARE_ENGINEER,
                PRODUCT_MANAGER, FRONTEND_ENGINEER, QA_ENGINEER };
    }

    public static ExpertRole fromStep(int step) {
        ExpertRole[] order = reviewOrder();
        if (step < 0 || step >= order.length) return null;
        return order[step];
    }
}
