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
            "- Is the scope appropriate — not too much, not too little?");

    private final String displayName;
    private final String reviewPrompt;

    ExpertRole(String displayName, String reviewPrompt) {
        this.displayName = displayName;
        this.reviewPrompt = reviewPrompt;
    }

    public String getDisplayName() { return displayName; }
    public String getReviewPrompt() { return reviewPrompt; }

    public static ExpertRole[] reviewOrder() {
        return new ExpertRole[] { SOFTWARE_ARCHITECT, DATA_ANALYST, SOFTWARE_ENGINEER, PRODUCT_MANAGER };
    }

    public static ExpertRole fromStep(int step) {
        ExpertRole[] order = reviewOrder();
        if (step < 0 || step >= order.length) return null;
        return order[step];
    }
}
