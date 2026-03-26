package com.sitemanager.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sitemanager.model.SiteSettings;
import com.sitemanager.repository.SiteSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

@Service
public class ClaudeService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeService.class);
    private static final int MAX_LOG_PROMPT_LENGTH = 500;
    private static final int MAX_LOG_RESPONSE_LENGTH = 1000;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final Map<String, String> sessionMap = new ConcurrentHashMap<>();
    private final AtomicLong requestCounter = new AtomicLong(0);

    private final SiteSettingsRepository settingsRepository;

    @Value("${app.claude-cli-path:claude}")
    private String claudeCliPath;

    @Value("${app.workspace-dir:/workspace}")
    private String workspaceDir;

    @Value("${app.git-ssh-key-path:}")
    private String gitSshKeyPath;

    @Value("${app.claude-timeout-minutes:30}")
    private int claudeTimeoutMinutes;

    @Value("${app.claude-verbose:false}")
    private boolean claudeVerbose;

    @Value("${app.claude-model:}")
    private String claudeModelDefault;

    @Value("${app.claude-model-expert:}")
    private String claudeModelExpertDefault;

    @Value("${app.claude-max-turns-expert:3}")
    private int claudeMaxTurnsExpertDefault;

    public ClaudeService(SiteSettingsRepository settingsRepository) {
        this.settingsRepository = settingsRepository;
    }

    private SiteSettings getSettings() {
        return settingsRepository.findAll().stream().findFirst().orElse(new SiteSettings());
    }

    /**
     * Resolve the model to use for main operations.
     * Settings DB takes priority, then application.yml / env var, then CLI default.
     */
    private String resolveModel() {
        try {
            String fromSettings = getSettings().getClaudeModel();
            if (fromSettings != null && !fromSettings.isBlank()) return fromSettings;
        } catch (Exception e) {
            log.debug("Could not read model from settings: {}", e.getMessage());
        }
        return claudeModelDefault;
    }

    /**
     * Resolve the model to use for expert reviews.
     * Settings DB takes priority, then application.yml / env var, then falls back to main model.
     */
    private String resolveExpertModel() {
        try {
            String fromSettings = getSettings().getClaudeModelExpert();
            if (fromSettings != null && !fromSettings.isBlank()) return fromSettings;
        } catch (Exception e) {
            log.debug("Could not read expert model from settings: {}", e.getMessage());
        }
        if (claudeModelExpertDefault != null && !claudeModelExpertDefault.isBlank()) return claudeModelExpertDefault;
        return resolveModel();
    }

    /**
     * Resolve max turns for expert reviews.
     * Settings DB takes priority, then application.yml / env var default.
     */
    private int resolveExpertMaxTurns() {
        try {
            Integer fromSettings = getSettings().getClaudeMaxTurnsExpert();
            if (fromSettings != null && fromSettings > 0) return fromSettings;
        } catch (Exception e) {
            log.debug("Could not read expert max turns from settings: {}", e.getMessage());
        }
        return claudeMaxTurnsExpertDefault;
    }

    public String generateSessionId() {
        return UUID.randomUUID().toString();
    }

    public String getMainRepoDir() {
        return workspaceDir + "/main-repo";
    }

    /**
     * Create a new branch from the current HEAD in the given repo directory.
     */
    public void createBranch(String repoDir, String branchName) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("git", "checkout", "-b", branchName);
        pb.directory(new File(repoDir));
        pb.redirectErrorStream(true);
        pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                log.info("Git checkout -b: {}", line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Failed to create branch " + branchName + ": " + output.toString().trim());
        }
        log.info("Created branch {} in {}", branchName, repoDir);
    }

    public CompletableFuture<String> evaluateSuggestion(String suggestionTitle, String suggestionDescription,
                                                         String repoUrl, String sessionId,
                                                         String workingDir,
                                                         Consumer<String> progressCallback) {
        String prompt = String.format(
                "You are evaluating a site suggestion for a repository at: %s\n\n" +
                "Suggestion Title: %s\n" +
                "Suggestion Description: %s\n\n" +
                "Please evaluate this suggestion:\n" +
                "1. Is this suggestion detailed enough to implement? Does it clearly describe what changes are needed?\n" +
                "2. If NOT detailed enough, ask specific clarifying questions to the user.\n" +
                "3. If it IS detailed enough, look at the repository and create a concrete implementation plan.\n\n" +
                "DUAL-LEVEL PLAN RULES:\n" +
                "- The plan and tasks must have TWO levels of detail:\n" +
                "  1. LOW-LEVEL (internal/technical): Detailed technical implementation specifics including file names, classes, methods, " +
                "database changes, API endpoints, frameworks, and step-by-step coding instructions. This is what drives execution.\n" +
                "  2. HIGH-LEVEL (user-facing display): Plain, non-technical language describing features, behaviors, and outcomes " +
                "from the user's perspective. No programming languages, file names, class names, or technical details.\n" +
                "- Questions to users should always be in plain, non-technical language about desired behavior and outcomes.\n\n" +
                "Respond in this JSON format:\n" +
                "If clarification needed:\n" +
                "{\"status\": \"NEEDS_CLARIFICATION\", " +
                "\"message\": \"brief summary of what you need to know\", " +
                "\"questions\": [\"specific question 1\", \"specific question 2\", ...]}\n\n" +
                "If ready to plan:\n" +
                "{\"status\": \"PLAN_READY\", " +
                "\"message\": \"your response to the user (high-level, non-technical)\", " +
                "\"plan\": \"detailed low-level technical implementation plan with file names, classes, methods, database schema changes, " +
                "API endpoints, specific code changes needed, and technical approach\", " +
                "\"planDisplaySummary\": \"brief high-level summary of the implementation in plain non-technical language for the user\", " +
                "\"tasks\": [\n" +
                "  {\"title\": \"low-level technical task name (e.g. Add emailNotifications column to site_settings table)\", " +
                "\"description\": \"detailed technical description with specific files, classes, methods to modify and how\", " +
                "\"displayTitle\": \"high-level user-facing task name (e.g. Add email notification option to settings)\", " +
                "\"displayDescription\": \"plain language description of what this changes from the user's perspective\", " +
                "\"estimatedMinutes\": number},\n" +
                "  ...\n" +
                "]}\n\n" +
                "IMPORTANT: When status is NEEDS_CLARIFICATION, you MUST include a \"questions\" array with each clarifying question as a separate string element. Each question should be self-contained and specific.\n" +
                "When status is PLAN_READY, you MUST include a \"tasks\" array that breaks the plan into ordered implementation steps. " +
                "Each task should be a concrete, actionable unit of work with a realistic time estimate in minutes. " +
                "The low-level title/description should include specific technical details (file paths, method names, database operations). " +
                "The displayTitle/displayDescription should be completely non-technical. " +
                "Order tasks by implementation sequence. Typically 3-8 tasks is appropriate.",
                repoUrl != null ? repoUrl : "not configured",
                suggestionTitle,
                suggestionDescription
        );

        return sendToClaudeAsync(prompt, sessionId, workingDir, null, progressCallback, "evaluate", resolveModel(), 0);
    }

    public CompletableFuture<String> continueConversation(String sessionId, String userMessage,
                                                           String conversationContext,
                                                           String workingDir,
                                                           Consumer<String> progressCallback) {
        return sendToClaudeAsync(userMessage, sessionId, workingDir, conversationContext, progressCallback, "continue", resolveModel(), 0);
    }

    public CompletableFuture<String> executePlan(String sessionId, String plan, String tasksJson,
                                                  String workingDir,
                                                  Consumer<String> progressCallback) {
        String prompt = String.format(
                "Execute the following implementation plan in the repository at %s.\n\n" +
                "Plan:\n%s\n\n" +
                "Tasks (execute in order):\n%s\n\n" +
                "COMMUNICATION RULES:\n" +
                "- All message fields in your JSON output MUST be written in plain, non-technical language.\n" +
                "- NEVER mention programming languages, frameworks, libraries, file names, class names, or technical details in messages.\n" +
                "- Describe progress in terms of what is changing from the user's perspective.\n" +
                "  Good: \"Added the new option to the settings page\"\n" +
                "  Bad: \"Created SiteSettings.java field and REST endpoint\"\n\n" +
                "Instructions:\n" +
                "1. Execute each task in order by its task number\n" +
                "2. Write unit tests for all new code\n" +
                "3. Run existing tests to ensure nothing is broken\n\n" +
                "For EACH task, follow this workflow:\n\n" +
                "Step A — Start the task:\n" +
                "{\"taskOrder\": number, \"status\": \"IN_PROGRESS\", \"message\": \"starting description\"}\n\n" +
                "Step B — Implement the task (write code, make changes)\n\n" +
                "Step C — Review the task. After implementing, review your own code changes for this task:\n" +
                "  - Verify the code changes actually fulfill the task requirements\n" +
                "  - Check for bugs, missing edge cases, or incomplete implementation\n" +
                "  - Run any relevant tests to confirm correctness\n" +
                "Output a review status:\n" +
                "{\"taskOrder\": number, \"status\": \"REVIEWING\", \"message\": \"reviewing: what you checked\"}\n\n" +
                "Step D — If the review passes, mark the task completed:\n" +
                "{\"taskOrder\": number, \"status\": \"COMPLETED\", \"message\": \"what was done and verified\"}\n" +
                "If the review finds issues, fix them and re-review before marking completed.\n\n" +
                "Step E — If a task cannot be completed, mark it failed:\n" +
                "{\"taskOrder\": number, \"status\": \"FAILED\", \"message\": \"what went wrong\"}\n\n" +
                "After ALL tasks pass review, output a final summary:\n" +
                "{\"status\": \"COMPLETED\", \"message\": \"summary\", \"testsRun\": number, \"testsPassed\": number}\n" +
                "If the overall execution fails:\n" +
                "{\"status\": \"FAILED\", \"message\": \"what went wrong\"}",
                workingDir, plan, tasksJson != null ? tasksJson : "No structured tasks — follow the plan above."
        );

        return sendToClaudeAsync(prompt, sessionId, workingDir, null, progressCallback, "execute", resolveModel(), 0);
    }

    /**
     * Execute a single task from the plan. Called one task at a time so experts
     * can review each task's output before proceeding to the next.
     */
    public CompletableFuture<String> executeSingleTask(String sessionId, String plan,
                                                         int taskOrder, String taskTitle,
                                                         String taskDescription,
                                                         int totalTasks,
                                                         String completedTasksSummary,
                                                         String workingDir,
                                                         Consumer<String> progressCallback) {
        String prompt = String.format(
                "Execute ONLY task %d of %d in the repository at %s.\n\n" +
                "Overall Plan:\n%s\n\n" +
                "%s" +
                "YOUR TASK (Task %d of %d):\n" +
                "Title: %s\n" +
                "Description: %s\n\n" +
                "COMMUNICATION RULES:\n" +
                "- All message fields in your JSON output MUST be written in plain, non-technical language.\n" +
                "- NEVER mention programming languages, frameworks, libraries, file names, class names, or technical details in messages.\n" +
                "- Describe progress in terms of what is changing from the user's perspective.\n\n" +
                "Instructions:\n" +
                "1. Execute ONLY this single task — do NOT work on other tasks\n" +
                "2. Write unit tests for all new code in this task\n" +
                "3. Run existing tests to ensure nothing is broken\n\n" +
                "Follow this workflow:\n\n" +
                "Step A — Start the task:\n" +
                "{\"taskOrder\": %d, \"status\": \"IN_PROGRESS\", \"message\": \"starting description\"}\n\n" +
                "Step B — Implement the task (write code, make changes)\n\n" +
                "Step C — Review the task. After implementing, review your own code changes:\n" +
                "  - Verify the code changes actually fulfill the task requirements\n" +
                "  - Check for bugs, missing edge cases, or incomplete implementation\n" +
                "  - Run any relevant tests to confirm correctness\n" +
                "Output a review status:\n" +
                "{\"taskOrder\": %d, \"status\": \"REVIEWING\", \"message\": \"reviewing: what you checked\"}\n\n" +
                "Step D — If the review passes, mark the task completed:\n" +
                "{\"taskOrder\": %d, \"status\": \"COMPLETED\", \"message\": \"what was done and verified\"}\n" +
                "If the review finds issues, fix them and re-review before marking completed.\n\n" +
                "Step E — If the task cannot be completed, mark it failed:\n" +
                "{\"taskOrder\": %d, \"status\": \"FAILED\", \"message\": \"what went wrong\"}\n\n" +
                "IMPORTANT: Only work on task %d. Do NOT proceed to other tasks.",
                taskOrder, totalTasks, workingDir,
                plan,
                completedTasksSummary != null && !completedTasksSummary.isBlank() ?
                        "Previously completed tasks:\n" + completedTasksSummary + "\n\n" : "",
                taskOrder, totalTasks,
                taskTitle,
                taskDescription != null ? taskDescription : taskTitle,
                taskOrder, taskOrder, taskOrder, taskOrder, taskOrder
        );

        return sendToClaudeAsync(prompt, sessionId, workingDir, null, progressCallback,
                "execute-task-" + taskOrder, resolveModel(), 0);
    }

    /**
     * Have experts review a completed task's actual code changes to verify
     * the task was properly completed before moving to the next task.
     */
    public CompletableFuture<String> reviewTaskCompletion(String sessionId, String expertDisplayName,
                                                            String expertPrompt, String suggestionTitle,
                                                            int taskOrder, String taskTitle,
                                                            String taskDescription, String plan,
                                                            String workingDir,
                                                            Consumer<String> progressCallback) {
        String prompt = String.format(
                "%s\n\n" +
                "You are reviewing the ACTUAL CODE CHANGES made for a specific task.\n\n" +
                "Suggestion: %s\n" +
                "Overall Plan:\n%s\n\n" +
                "Task %d being reviewed:\n" +
                "Title: %s\n" +
                "Description: %s\n\n" +
                "INSTRUCTIONS:\n" +
                "1. Examine the current state of the code in the repository\n" +
                "2. Look at recent git changes (use git diff or git log) to see what was actually changed for this task\n" +
                "3. Verify the implementation matches the task requirements\n" +
                "4. Check for bugs, missing functionality, or incomplete work\n" +
                "5. Run tests if applicable to confirm correctness\n\n" +
                "REVIEW SEVERITY RULES:\n" +
                "- ONLY flag CRITICAL or MAJOR issues. Do NOT nitpick.\n" +
                "  CRITICAL: Would cause data loss, security vulnerabilities, system crashes, or broken core functionality.\n" +
                "  MAJOR: Significant bugs, missing key requirements, or incomplete implementation.\n" +
                "- If the task was completed reasonably well, APPROVE it. Most tasks should be approved.\n" +
                "- Bias toward action — a shipped improvement beats a perfect implementation.\n\n" +
                "Respond in this JSON format:\n" +
                "If the task is properly completed:\n" +
                "{\"status\": \"APPROVED\", \"analysis\": \"what you verified and why it passes\", " +
                "\"message\": \"concise NON-TECHNICAL summary for the user\"}\n\n" +
                "If the task has CRITICAL or MAJOR issues that must be fixed:\n" +
                "{\"status\": \"NEEDS_FIXES\", \"analysis\": \"what issues you found\", " +
                "\"fixes\": \"specific technical description of what needs to be fixed\", " +
                "\"message\": \"concise NON-TECHNICAL summary for the user\"}",
                expertPrompt,
                suggestionTitle,
                plan,
                taskOrder,
                taskTitle,
                taskDescription != null ? taskDescription : taskTitle
        );

        return sendToClaudeAsync(prompt, sessionId, workingDir, null, progressCallback,
                "task-review:" + expertDisplayName + ":task-" + taskOrder, resolveExpertModel(), resolveExpertMaxTurns());
    }

    public CompletableFuture<String> expertReview(String sessionId, String expertDisplayName,
                                                    String expertPrompt, String suggestionTitle,
                                                    String suggestionDescription, String plan,
                                                    String tasksJson, String previousNotes,
                                                    String workingDir,
                                                    Consumer<String> progressCallback) {
        String prompt = String.format(
                "%s\n\n" +
                "Suggestion Title: %s\n" +
                "Suggestion Description: %s\n\n" +
                "Current Plan:\n%s\n\n" +
                "%s" +
                "%s" +
                "REVIEW SEVERITY RULES:\n" +
                "- ONLY flag CRITICAL, MAJOR, or MEDIUM issues. Do NOT nitpick.\n" +
                "  CRITICAL: Would cause data loss, security vulnerabilities, system crashes, or broken core functionality.\n" +
                "  MAJOR: Significant bugs, missing key requirements, architectural problems that would require rework.\n" +
                "  MEDIUM: Notable gaps in error handling, performance concerns under real usage, or missing edge cases that users will likely hit.\n" +
                "- Do NOT propose changes for: style preferences, minor naming conventions, theoretical concerns that are unlikely in practice, " +
                "or optimizations that don't matter at current scale.\n" +
                "- If the plan is reasonable and has no critical/major/medium issues, APPROVE it. Most plans should be approved.\n" +
                "- Bias toward action — a shipped improvement beats a perfect plan.\n\n" +
                "DUAL-LEVEL DETAIL RULES:\n" +
                "- The plan you are reviewing contains LOW-LEVEL technical details (file names, classes, methods, etc.). " +
                "Use these details for your analysis — review them thoroughly from your expertise area.\n" +
                "- Your 'analysis' field should reference technical specifics when relevant to your expertise.\n" +
                "- Your 'message' field (shown to the user) MUST be written in plain, non-technical language — " +
                "describe features, behaviors, and outcomes only. NEVER mention file names, classes, APIs, or technical details in the message.\n" +
                "- Questions to users should be about desired behavior and outcomes, not technical choices.\n\n" +
                "PARTICIPATION RULES:\n" +
                "- Provide a concise, focused analysis from your area of expertise.\n" +
                "- Your analysis should be 2-3 sentences covering the most important observations from your domain.\n" +
                "- When approving, briefly state what you evaluated and why it's acceptable.\n" +
                "- Do NOT give generic approvals like 'looks good'. Be specific but brief.\n\n" +
                "Respond in this JSON format:\n" +
                "If the plan looks good from your perspective:\n" +
                "{\"status\": \"APPROVED\", \"analysis\": \"your focused technical analysis — what you evaluated and why it passes\", \"message\": \"concise NON-TECHNICAL summary for the user\"}\n\n" +
                "If you find CRITICAL or MAJOR issues that must be fixed:\n" +
                "{\"status\": \"CHANGES_PROPOSED\", \"analysis\": \"your technical analysis of the issues found\", " +
                "\"proposedChanges\": \"technical description of what should change\", " +
                "\"revisedPlan\": \"updated low-level technical plan\", " +
                "\"revisedPlanDisplaySummary\": \"updated high-level non-technical summary for the user\", " +
                "\"revisedTasks\": [{\"title\": \"low-level technical task name\", \"description\": \"detailed technical description\", " +
                "\"displayTitle\": \"high-level user-facing task name\", \"displayDescription\": \"plain language description\", " +
                "\"estimatedMinutes\": number}, ...], " +
                "\"message\": \"concise NON-TECHNICAL summary for the user\"}\n\n" +
                "If you need the user to answer questions before you can complete your review:\n" +
                "{\"status\": \"NEEDS_CLARIFICATION\", \"analysis\": \"what you've found so far\", " +
                "\"questions\": [\"high-level non-technical question 1\", \"high-level non-technical question 2\"], " +
                "\"message\": \"brief summary of what you need to know\"}\n\n" +
                "IMPORTANT: When proposing changes, you MUST include revisedTasks with the COMPLETE task list (not just changed tasks). " +
                "Each task MUST have both low-level (title/description) and high-level (displayTitle/displayDescription) fields. " +
                "When asking questions, keep them high-level and non-technical. " +
                "Only propose CHANGES_PROPOSED for critical or major issues — approve with notes for medium issues.",
                expertPrompt,
                suggestionTitle,
                suggestionDescription,
                plan,
                tasksJson != null ? "Current Tasks:\n" + tasksJson + "\n\n" : "",
                previousNotes != null && !previousNotes.isBlank() ?
                        "Previous expert reviews:\n" + previousNotes + "\n\n" : ""
        );

        return sendToClaudeAsync(prompt, sessionId, workingDir, null, progressCallback,
                "expert-review:" + expertDisplayName, resolveExpertModel(), resolveExpertMaxTurns());
    }

    public CompletableFuture<String> reviewExpertFeedback(String sessionId, String expertDisplayName,
                                                            String expertAnalysis, String proposedChanges,
                                                            String currentPlan, String reviewerRole,
                                                            String reviewerPrompt, String workingDir,
                                                            Consumer<String> progressCallback) {
        String prompt = String.format(
                "%s\n\n" +
                "A %s has reviewed an implementation plan and proposed changes. " +
                "As the %s, evaluate whether these changes are warranted.\n\n" +
                "Current plan:\n%s\n\n" +
                "The %s's analysis:\n%s\n\n" +
                "Their proposed changes:\n%s\n\n" +
                "EVALUATION CRITERIA:\n" +
                "- Do the proposed changes address a CRITICAL or MAJOR issue? Only approve changes that fix real problems.\n" +
                "- Would the changes add unnecessary complexity or scope creep?\n" +
                "- Are the changes compatible with the overall approach and goals?\n" +
                "- From your perspective as %s, do these changes improve the plan for the user?\n\n" +
                "Respond in this JSON format:\n" +
                "{\"valid\": true/false, \"notes\": \"your assessment of why the changes are or aren't valuable\", " +
                "\"apply\": true/false}\n\n" +
                "Set apply=true ONLY if the changes fix a critical or major issue. " +
                "Reject changes that are cosmetic, over-engineered, or add scope without clear value.",
                reviewerPrompt, expertDisplayName, reviewerRole,
                currentPlan, expertDisplayName, expertAnalysis, proposedChanges, reviewerRole
        );

        return sendToClaudeAsync(prompt, sessionId, workingDir, null, progressCallback,
                "review-feedback:" + reviewerRole + "<-" + expertDisplayName, resolveExpertModel(), resolveExpertMaxTurns());
    }

    private CompletableFuture<String> sendToClaudeAsync(String prompt, String sessionId,
                                                         String workingDir, String conversationContext,
                                                         Consumer<String> progressCallback,
                                                         String operationType, String model, int maxTurns) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return sendToClaude(prompt, sessionId, workingDir, conversationContext, progressCallback, operationType, model, maxTurns);
            } catch (Exception e) {
                log.error("[CLAUDE-{}] session={} ERROR: {}", operationType, sessionId, e.getMessage(), e);
                return "{\"status\": \"ERROR\", \"message\": \"" +
                        e.getMessage().replace("\"", "\\\"") + "\"}";
            }
        }, executor);
    }

    private String sendToClaude(String prompt, String sessionId, String workingDir,
                                 String conversationContext,
                                 Consumer<String> progressCallback,
                                 String operationType, String model, int maxTurns) throws Exception {
        long requestId = requestCounter.incrementAndGet();
        long startTime = System.currentTimeMillis();
        String logPrefix = String.format("[CLAUDE-REQ-%d][%s]", requestId, operationType);

        // Expert reviews and feedback always get a fresh session (no resume)
        boolean isExpertOp = operationType.startsWith("expert-review:") || operationType.startsWith("review-feedback:");
        String cliSessionId = isExpertOp ? null : sessionMap.get(sessionId);
        boolean isResume = cliSessionId != null;

        // Build command with optional model and max-turns flags
        List<String> command = new java.util.ArrayList<>();
        command.add(claudeCliPath);
        if (isResume) {
            command.add("--resume");
            command.add(cliSessionId);
        }
        command.add("-p");
        command.add(prompt);
        command.add("--output-format");
        command.add("json");
        if (model != null && !model.isBlank()) {
            command.add("--model");
            command.add(model);
        }
        if (maxTurns > 0) {
            command.add("--max-turns");
            command.add(String.valueOf(maxTurns));
        }
        if (claudeVerbose) {
            command.add("--verbose");
        }
        command.add("--dangerously-skip-permissions");

        ProcessBuilder pb = new ProcessBuilder(command);

        if (workingDir != null) {
            pb.directory(new File(workingDir));
        }

        // Propagate SSH key to Claude CLI subprocess so git operations use SSH auth
        String sshKeyPath = resolveGitSshKeyPath();
        if (sshKeyPath != null) {
            String sshCommand = "ssh -i " + sshKeyPath + " -o StrictHostKeyChecking=no";
            pb.environment().put("GIT_SSH_COMMAND", sshCommand);
        }

        pb.redirectErrorStream(true);
        pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));

        // Log the request
        log.info("{} session={} resume={} model={} maxTurns={} verbose={} workDir={} promptLength={}",
                logPrefix, sessionId, isResume,
                (model != null && !model.isBlank()) ? model : "default",
                maxTurns > 0 ? maxTurns : "unlimited",
                claudeVerbose,
                workingDir, prompt.length());
        if (claudeVerbose) {
            log.info("{} command: {}", logPrefix, String.join(" ", command));
            log.info("{} prompt: {}", logPrefix, truncate(prompt, MAX_LOG_PROMPT_LENGTH));
        } else {
            log.debug("{} prompt: {}", logPrefix, truncate(prompt, MAX_LOG_PROMPT_LENGTH));
        }

        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                if (claudeVerbose) {
                    log.info("{} [stdout] {}", logPrefix, line);
                }
                if (progressCallback != null && !isCliJsonEnvelope(line)) {
                    progressCallback.accept(line);
                }
            }
        }

        boolean completed = process.waitFor(claudeTimeoutMinutes, TimeUnit.MINUTES);
        if (!completed) {
            process.destroyForcibly();
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("{} TIMEOUT after {}ms (limit={}min) — process killed",
                    logPrefix, elapsed, claudeTimeoutMinutes);
            throw new RuntimeException("Claude CLI timed out after " + claudeTimeoutMinutes + " minutes");
        }

        int exitCode = process.exitValue();
        long elapsed = System.currentTimeMillis() - startTime;
        String rawOutput = output.toString().trim();

        // Log raw output size and timing
        log.info("{} completed in {}ms exitCode={} responseLength={}",
                logPrefix, elapsed, exitCode, rawOutput.length());

        // Try to parse as JSON to extract session_id and result
        String resultText = rawOutput;
        String parsedCliSessionId = null;

        try {
            String jsonStr = extractJsonObject(rawOutput);
            if (jsonStr != null) {
                JsonNode root = objectMapper.readTree(jsonStr);

                // Extract the CLI session ID for future --resume calls
                if (root.has("session_id")) {
                    parsedCliSessionId = root.get("session_id").asText();
                }

                // Extract the actual result text
                if (root.has("result")) {
                    resultText = root.get("result").asText();
                }

                // Check for error responses (e.g., dead session)
                if (root.has("is_error") && root.get("is_error").asBoolean()) {
                    String errorMsg = root.has("result") ? root.get("result").asText() : rawOutput;
                    if (isResume && isDeadSessionError(errorMsg)) {
                        log.warn("{} dead session detected, rebuilding context", logPrefix);
                        return handleDeadSession(prompt, sessionId, workingDir,
                                conversationContext, progressCallback, operationType, model, maxTurns);
                    }
                    log.warn("{} error response: {}", logPrefix, truncate(errorMsg, MAX_LOG_RESPONSE_LENGTH));
                }
            }
        } catch (Exception e) {
            log.debug("{} could not parse output as JSON: {}", logPrefix, e.getMessage());
        }

        // Fallback: check raw output for dead session error
        if (isResume && isDeadSessionError(rawOutput)) {
            log.warn("{} dead session detected (fallback check), rebuilding context", logPrefix);
            return handleDeadSession(prompt, sessionId, workingDir,
                    conversationContext, progressCallback, operationType, model, maxTurns);
        }

        // Store the CLI session ID for future --resume calls
        if (parsedCliSessionId != null && !parsedCliSessionId.isBlank()) {
            sessionMap.put(sessionId, parsedCliSessionId);
            log.debug("{} stored CLI session: {}", logPrefix, parsedCliSessionId);
        }

        if (exitCode != 0) {
            log.warn("{} non-zero exit code: {}", logPrefix, exitCode);
        }

        // Log response summary
        if (claudeVerbose) {
            log.info("{} response: {}", logPrefix, truncate(resultText, MAX_LOG_RESPONSE_LENGTH));
        } else {
            log.debug("{} response: {}", logPrefix, truncate(resultText, MAX_LOG_RESPONSE_LENGTH));
        }

        return resultText;
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) return "null";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...[truncated, total=" + text.length() + "]";
    }

    /**
     * Detect lines that are part of the Claude CLI JSON result envelope
     * (e.g. {"type":"result","subtype":"success",...}) so they are not
     * forwarded as raw progress to the UI.
     */
    private static boolean isCliJsonEnvelope(String line) {
        if (line == null) return false;
        String trimmed = line.trim();
        return trimmed.startsWith("{") && trimmed.contains("\"type\"") &&
                (trimmed.contains("\"result\"") || trimmed.contains("\"system\""));
    }

    private boolean isDeadSessionError(String output) {
        return output != null && (
                output.contains("No conversation found") ||
                output.contains("session not found") ||
                output.contains("Session not found"));
    }

    private String handleDeadSession(String prompt, String sessionId, String workingDir,
                                      String conversationContext,
                                      Consumer<String> progressCallback,
                                      String operationType, String model, int maxTurns) throws Exception {
        log.warn("[CLAUDE-{}] session={} is dead, starting fresh session with conversation context",
                operationType, sessionId);
        sessionMap.remove(sessionId);

        String rebuiltPrompt;
        if (conversationContext != null && !conversationContext.isBlank()) {
            rebuiltPrompt = "Here is the prior conversation history for context. " +
                    "Continue naturally from where the conversation left off.\n\n" +
                    "--- CONVERSATION HISTORY ---\n" +
                    conversationContext +
                    "\n--- END HISTORY ---\n\n" +
                    "Now, the user says:\n\n" + prompt;
        } else {
            rebuiltPrompt = prompt;
        }

        // Retry with a fresh session (no resume, no context to avoid infinite loop)
        return sendToClaude(rebuiltPrompt, sessionId, workingDir, null, progressCallback, operationType + "-retry", model, maxTurns);
    }

    /**
     * Extract the outermost JSON object from output that may contain
     * non-JSON lines (warnings, verbose output, etc.) before or after the JSON.
     */
    private String extractJsonObject(String raw) {
        int start = raw.indexOf('{');
        if (start < 0) return null;

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = start; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (!inString) {
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return raw.substring(start, i + 1);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Clone the repository into main-repo/ for the initial Claude session workspace.
     */
    public String cloneMainRepository(String repoUrl) throws Exception {
        String targetDir = workspaceDir + "/main-repo";
        return gitClone(repoUrl, targetDir);
    }

    /**
     * Pull the latest changes in main-repo/. If the repo doesn't exist yet, clone it.
     * Returns true if the pull brought in new changes (HEAD moved).
     */
    public boolean pullMainRepository(String repoUrl) throws Exception {
        String targetDir = workspaceDir + "/main-repo";
        File dir = new File(targetDir);
        File gitDir = new File(targetDir, ".git");

        if (!dir.exists() || !gitDir.exists()) {
            cloneMainRepository(repoUrl);
            return true;
        }

        // Get HEAD before pull
        String headBefore = getHeadCommit(targetDir);

        ProcessBuilder pb = new ProcessBuilder("git", "pull");
        pb.directory(dir);
        pb.redirectErrorStream(true);
        pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));

        String sshKeyPath = resolveGitSshKeyPath();
        if (sshKeyPath != null) {
            String sshCommand = "ssh -i " + sshKeyPath + " -o StrictHostKeyChecking=no";
            pb.environment().put("GIT_SSH_COMMAND", sshCommand);
        }

        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("Git pull main-repo: {}", line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            log.warn("Git pull failed (exit {}), falling back to fresh clone", exitCode);
            cloneMainRepository(repoUrl);
            return true;
        }

        // Get HEAD after pull
        String headAfter = getHeadCommit(targetDir);
        boolean changed = !headAfter.equals(headBefore);
        if (changed) {
            log.info("main-repo updated: {} -> {}", headBefore, headAfter);
        } else {
            log.info("main-repo already up to date at {}", headAfter);
        }
        return changed;
    }

    /**
     * Merge the main-repo default branch into a suggestion repo's working branch.
     * Returns true if the merge brought in new changes, false if already up to date.
     * Throws on merge conflict or error.
     */
    public boolean mergeSuggestionRepoWithMain(String suggestionRepoDir) throws Exception {
        File dir = new File(suggestionRepoDir);
        if (!dir.exists() || !new File(suggestionRepoDir, ".git").exists()) {
            log.warn("Suggestion repo does not exist: {}", suggestionRepoDir);
            return false;
        }

        String headBefore = getHeadCommit(suggestionRepoDir);

        // Fetch the latest from origin
        ProcessBuilder fetchPb = new ProcessBuilder("git", "fetch", "origin");
        fetchPb.directory(dir);
        fetchPb.redirectErrorStream(true);
        fetchPb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        String sshKeyPath = resolveGitSshKeyPath();
        if (sshKeyPath != null) {
            fetchPb.environment().put("GIT_SSH_COMMAND",
                    "ssh -i " + sshKeyPath + " -o StrictHostKeyChecking=no");
        }
        Process fetchProcess = fetchPb.start();
        consumeStream(fetchProcess.getInputStream());
        fetchProcess.waitFor();

        // Detect default branch name (main or master)
        String defaultBranch = detectDefaultBranch(suggestionRepoDir);

        // Merge origin/<default-branch> into current branch
        ProcessBuilder mergePb = new ProcessBuilder("git", "merge", "origin/" + defaultBranch);
        mergePb.directory(dir);
        mergePb.redirectErrorStream(true);
        mergePb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        Process mergeProcess = mergePb.start();

        StringBuilder mergeOutput = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(mergeProcess.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                mergeOutput.append(line).append("\n");
                log.info("Git merge suggestion-repo: {}", line);
            }
        }

        int exitCode = mergeProcess.waitFor();
        if (exitCode != 0) {
            // Abort the failed merge so the repo is left clean
            ProcessBuilder abortPb = new ProcessBuilder("git", "merge", "--abort");
            abortPb.directory(dir);
            abortPb.redirectErrorStream(true);
            Process abortProcess = abortPb.start();
            consumeStream(abortProcess.getInputStream());
            abortProcess.waitFor();
            throw new RuntimeException("Merge conflict in " + suggestionRepoDir +
                    ": " + mergeOutput.toString().trim());
        }

        String headAfter = getHeadCommit(suggestionRepoDir);
        boolean changed = !headAfter.equals(headBefore);
        if (changed) {
            log.info("Suggestion repo {} merged main: {} -> {}", suggestionRepoDir, headBefore, headAfter);
        }
        return changed;
    }

    /**
     * Find all suggestion repo directories that currently exist on disk.
     */
    public List<String> findSuggestionRepoDirs() {
        File workspace = new File(workspaceDir);
        List<String> dirs = new java.util.ArrayList<>();
        if (!workspace.exists()) return dirs;

        File[] files = workspace.listFiles();
        if (files == null) return dirs;

        for (File f : files) {
            if (f.isDirectory() && f.getName().startsWith("suggestion-") && f.getName().endsWith("-repo")) {
                dirs.add(f.getAbsolutePath());
            }
        }
        return dirs;
    }

    private String getHeadCommit(String repoDir) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "HEAD");
        pb.directory(new File(repoDir));
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        process.waitFor();
        return output;
    }

    private String detectDefaultBranch(String repoDir) throws Exception {
        // Check if origin/main exists
        ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "--verify", "origin/main");
        pb.directory(new File(repoDir));
        pb.redirectErrorStream(true);
        Process process = pb.start();
        consumeStream(process.getInputStream());
        int exitCode = process.waitFor();
        return exitCode == 0 ? "main" : "master";
    }

    private void consumeStream(InputStream is) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            while (reader.readLine() != null) { /* drain */ }
        }
    }

    /**
     * Clone the repository into suggestion-{id}-repo/ when a suggestion is approved.
     */
    public String cloneRepository(String repoUrl, String suggestionId) throws Exception {
        String targetDir = workspaceDir + "/suggestion-" + suggestionId + "-repo";
        return gitClone(repoUrl, targetDir);
    }

    private String gitClone(String repoUrl, String targetDir) throws Exception {
        File dir = new File(targetDir);
        if (dir.exists()) {
            ProcessBuilder cleanup = new ProcessBuilder("rm", "-rf", targetDir);
            cleanup.start().waitFor();
        }

        String sshRepoUrl = toSshUrl(repoUrl);
        ProcessBuilder pb = new ProcessBuilder("git", "clone", sshRepoUrl, targetDir);
        pb.redirectErrorStream(true);
        pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));

        String sshKeyPath = resolveGitSshKeyPath();
        if (sshKeyPath != null) {
            String sshCommand = "ssh -i " + sshKeyPath + " -o StrictHostKeyChecking=no";
            pb.environment().put("GIT_SSH_COMMAND", sshCommand);
            log.info("Using SSH key for git clone: {}", sshKeyPath);
        }

        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("Git clone: {}", line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Failed to clone repository: exit code " + exitCode);
        }

        log.info("Repository cloned to {}", targetDir);
        return targetDir;
    }

    /**
     * Resolve the SSH key path. Uses the configured path if set, otherwise
     * auto-detects from the user's ~/.ssh directory (id_rsa, id_ed25519, etc.)
     * or falls back to the git config core.sshCommand key if present.
     */
    private String resolveGitSshKeyPath() {
        // Use explicitly configured path first
        if (gitSshKeyPath != null && !gitSshKeyPath.isBlank()) {
            File key = new File(gitSshKeyPath);
            if (key.exists() && key.isFile()) {
                return gitSshKeyPath;
            }
            log.warn("Configured SSH key not found at {}", gitSshKeyPath);
        }

        // Auto-detect from ~/.ssh
        String userHome = System.getProperty("user.home");
        if (userHome == null) {
            return null;
        }

        String sshDir = userHome + "/.ssh";
        String[] candidates = {"id_rsa", "id_ed25519", "id_ecdsa", "id_dsa"};
        for (String candidate : candidates) {
            File key = new File(sshDir, candidate);
            if (key.exists() && key.isFile()) {
                log.info("Auto-detected SSH key: {}", key.getAbsolutePath());
                return key.getAbsolutePath();
            }
        }

        // Check if git config has a key configured
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "config", "--get", "core.sshCommand");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor();
            if (!output.isBlank()) {
                log.info("Using git config core.sshCommand: {}", output);
                // Already handled by git itself, no need to override
                return null;
            }
        } catch (Exception e) {
            log.debug("Could not read git config core.sshCommand: {}", e.getMessage());
        }

        log.info("No SSH key found; git clone will use default SSH agent or credentials");
        return null;
    }

    /**
     * Convert an HTTPS GitHub URL to SSH format for SSH-based authentication.
     * If the URL is already SSH or not a recognized HTTPS GitHub URL, return as-is.
     */
    private String toSshUrl(String repoUrl) {
        if (repoUrl == null) {
            return repoUrl;
        }
        // Already SSH format
        if (repoUrl.startsWith("git@")) {
            return repoUrl;
        }
        // Convert https://github.com/owner/repo(.git) to git@github.com:owner/repo.git
        if (repoUrl.startsWith("https://github.com/") || repoUrl.startsWith("http://github.com/")) {
            String path = repoUrl.replaceFirst("https?://github\\.com/", "");
            if (!path.endsWith(".git")) {
                path = path + ".git";
            }
            return "git@github.com:" + path;
        }
        // Non-GitHub URL, return as-is
        return repoUrl;
    }

    /**
     * Push a branch to the remote origin.
     */
    public void pushBranch(String repoDir, String branchName) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("git", "push", "-u", "origin", branchName);
        pb.directory(new File(repoDir));
        pb.redirectErrorStream(true);
        pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));

        String sshKeyPath = resolveGitSshKeyPath();
        if (sshKeyPath != null) {
            pb.environment().put("GIT_SSH_COMMAND",
                    "ssh -i " + sshKeyPath + " -o StrictHostKeyChecking=no");
        }

        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                log.info("Git push: {}", line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Failed to push branch " + branchName + ": " + output.toString().trim());
        }
        log.info("Pushed branch {} in {}", branchName, repoDir);
    }

    /**
     * Get the commit log for a branch relative to the default branch.
     */
    public String getCommitLog(String repoDir) throws Exception {
        String defaultBranch = detectDefaultBranch(repoDir);
        ProcessBuilder pb = new ProcessBuilder("git", "log",
                "origin/" + defaultBranch + "..HEAD", "--pretty=format:%s");
        pb.directory(new File(repoDir));
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        process.waitFor();
        return output;
    }

    /**
     * Create a pull request on GitHub using the REST API.
     * Returns a map with "html_url" and "number" from the response.
     */
    public Map<String, Object> createGitHubPullRequest(String repoUrl, String branchName,
                                                        String title, String body,
                                                        String githubToken) throws Exception {
        // Extract owner/repo from the URL
        String ownerRepo = extractOwnerRepo(repoUrl);
        if (ownerRepo == null) {
            throw new RuntimeException("Cannot extract owner/repo from URL: " + repoUrl);
        }

        // Fetch the repository's actual default branch from the GitHub API
        HttpClient client = HttpClient.newHttpClient();
        String defaultBranch = fetchDefaultBranch(client, ownerRepo, githubToken);
        log.info("Using default branch '{}' as PR base for {}", defaultBranch, ownerRepo);

        String jsonBody = objectMapper.writeValueAsString(Map.of(
                "title", title,
                "body", body,
                "head", branchName,
                "base", defaultBranch
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.github.com/repos/" + ownerRepo + "/pulls"))
                .header("Authorization", "Bearer " + githubToken)
                .header("Accept", "application/vnd.github+json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 201) {
            throw new RuntimeException("GitHub API error (" + response.statusCode() + "): " + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        String htmlUrl = root.get("html_url").asText();
        int number = root.get("number").asInt();

        return Map.of("html_url", htmlUrl, "number", number);
    }

    /**
     * Fetch the default branch name for a GitHub repository via the REST API.
     * Falls back to "main" if the API call fails.
     */
    private String fetchDefaultBranch(HttpClient client, String ownerRepo, String githubToken) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/repos/" + ownerRepo))
                    .header("Authorization", "Bearer " + githubToken)
                    .header("Accept", "application/vnd.github+json")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode repo = objectMapper.readTree(response.body());
                JsonNode branch = repo.get("default_branch");
                if (branch != null && !branch.isNull()) {
                    return branch.asText();
                }
            }
            log.warn("Could not fetch default branch for {} (status={}), falling back to 'main'",
                    ownerRepo, response.statusCode());
        } catch (Exception e) {
            log.warn("Failed to fetch default branch for {}: {}, falling back to 'main'",
                    ownerRepo, e.getMessage());
        }
        return "main";
    }

    /**
     * Extract "owner/repo" from a GitHub URL.
     */
    private String extractOwnerRepo(String repoUrl) {
        if (repoUrl == null) return null;

        // Handle SSH format: git@github.com:owner/repo.git
        if (repoUrl.startsWith("git@github.com:")) {
            String path = repoUrl.substring("git@github.com:".length());
            if (path.endsWith(".git")) path = path.substring(0, path.length() - 4);
            return path;
        }

        // Handle HTTPS format: https://github.com/owner/repo(.git)
        if (repoUrl.contains("github.com/")) {
            String path = repoUrl.replaceFirst(".*github\\.com/", "");
            if (path.endsWith(".git")) path = path.substring(0, path.length() - 4);
            // Remove trailing slash
            if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            return path;
        }

        return null;
    }

    public void shutdown() {
        executor.shutdown();
    }
}
