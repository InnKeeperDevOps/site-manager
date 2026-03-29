# Expert Review Flow

The expert review pipeline automatically evaluates every AI-generated plan through a panel of 11 domain-specific AI experts before it reaches the approval stage. This document describes the full lifecycle using Mermaid diagrams.

---

## Suggestion Status Lifecycle

The expert review phase sits between the initial AI discussion and the plan-approval gate.

```mermaid
stateDiagram-v2
    [*] --> DRAFT : User submits suggestion

    DRAFT --> DISCUSSING : AI begins analysis

    DISCUSSING --> DISCUSSING : NEEDS_CLARIFICATION\n(user answers questions)
    DISCUSSING --> EXPERT_REVIEW : AI returns PLAN_READY

    EXPERT_REVIEW --> PLANNED : All experts finish

    PLANNED --> APPROVED : Admin approves
    PLANNED --> DENIED : Admin rejects

    APPROVED --> APPROVED : Queued (at capacity)
    APPROVED --> IN_PROGRESS : Slot available

    IN_PROGRESS --> TESTING : Tasks complete
    TESTING --> DEV_COMPLETE : Tests pass
    DEV_COMPLETE --> FINAL_REVIEW : PR created
    FINAL_REVIEW --> MERGED : PR merged

    DISCUSSING --> TIMED_OUT : Inactivity
    EXPERT_REVIEW --> TIMED_OUT : Inactivity
    DRAFT --> TIMED_OUT : Inactivity
```

---

## Expert Panel & Execution Order

Experts run **sequentially, one at a time**, so every expert can see the notes from all prior reviews.

```mermaid
flowchart LR
    SA[1. Software\nArchitect] --> SE[2. Security\nEngineer]
    SE --> IE[3. Infrastructure\nEngineer]
    IE --> DA[4. Data\nAnalyst]
    DA --> PE[5. Performance\nEngineer]
    PE --> DE[6. DevOps\nEngineer]
    DE --> SWE[7. Software\nEngineer]
    SWE --> FE[8. Frontend\nEngineer]
    FE --> UX[9. UX\nExpert]
    UX --> PM[10. Product\nManager]
    PM --> QA[11. QA\nEngineer]

    style SA fill:#4a90d9,color:#fff
    style SE fill:#4a90d9,color:#fff
    style IE fill:#4a90d9,color:#fff
    style DA fill:#50b88e,color:#fff
    style PE fill:#50b88e,color:#fff
    style DE fill:#50b88e,color:#fff
    style SWE fill:#e6a23c,color:#fff
    style FE fill:#e6a23c,color:#fff
    style UX fill:#e6a23c,color:#fff
    style PM fill:#d9534f,color:#fff
    style QA fill:#d9534f,color:#fff
```

---

## Expert Domain Mapping

Each expert belongs to a domain. When an expert proposes changes that get accepted, the domain is tracked so that affected domains can be targeted for re-review.

```mermaid
graph LR
    subgraph ARCHITECTURE
        SA[Software Architect]
        IE[Infrastructure Engineer]
    end

    subgraph SECURITY
        SE[Security Engineer]
    end

    subgraph DATA_PERF
        DA[Data Analyst]
        PE[Performance Engineer]
    end

    subgraph IMPLEMENTATION
        SWE[Software Engineer]
        DE[DevOps Engineer]
    end

    subgraph FRONTEND_UX
        FE[Frontend Engineer]
        UX[UX Expert]
    end

    subgraph PRODUCT_QA
        PM[Product Manager]
        QA[QA Engineer]
    end
```

### Affected Domains (Ripple Rules)

When a domain's expert proposes accepted changes, these related domains must also re-review:

```mermaid
graph LR
    ARCHITECTURE -->|triggers re-review of| IMPLEMENTATION
    SECURITY -->|triggers re-review of| ARCHITECTURE
    DATA_PERF -->|triggers re-review of| IMPLEMENTATION
    FRONTEND_UX -->|triggers re-review of| PRODUCT_QA

    IMPLEMENTATION -->|self only| IMPLEMENTATION
    PRODUCT_QA -->|self only| PRODUCT_QA
```

---

## Main Expert Review Pipeline

This is the top-level flow from entry to exit.

```mermaid
flowchart TD
    A[AI returns PLAN_READY] --> B[Set status = EXPERT_REVIEW\nstep=0, round=1, totalRounds=1\nnotes=null, planChanged=false]
    B --> C[startExpertReviewPipeline]
    C --> D[Broadcast expert review status via WebSocket]
    D --> E[runNextExpertReview]

    E --> F{step >= 11?\nAll experts done?}

    F -- No --> EA{step >= 3 AND\nno plan changes AND\nall notes are approvals?}
    EA -- Yes --> EB[Early exit: plan approved\nSkip remaining experts]
    EB --> T

    EA -- No --> G[Run current expert\nClaudeService.expertReview]
    G --> H[handleExpertReviewResponse]

    H --> N{Pipeline paused?\nClarification needed?}
    N -- Yes --> O[Wait for user answers\nvia WebSocket + API]
    O --> P[handleExpertClarificationAnswers]
    P --> H

    N -- No --> Q[Advance step]
    Q --> E

    F -- Yes --> R[Finalization Logic]
    R --> S{Plan changed\nthis round?}

    S -- "No" --> T[Status = PLANNED\nClear expert fields\nNotify admins]

    S -- "Yes" --> U{round < MAX_ROUNDS\nAND totalRounds < MAX_TOTAL?}
    U -- Yes --> V[Targeted re-review\nof affected domains]
    V --> W[runTargetedExpertRereviews\nRun affected experts sequentially]
    W --> R

    U -- No --> X[Force-finalize\nMax rounds reached]
    X --> T
```

---

## Individual Expert Response Handling

Each expert's response from Claude is parsed and handled according to its `status` field.

```mermaid
flowchart TD
    A[Expert response received\nfrom ClaudeService] --> B{Valid JSON\nwith structured response?}

    B -- No --> C[reInvokeExpertForDetailedReview\nRetry with reinforced prompt]
    C --> D{Retries < MAX_RETRIES?}
    D -- Yes --> A
    D -- No --> E[Accept current response\nRecord note and advance]

    B -- Yes --> F{Substantive analysis?\nMeets minimum length?}
    F -- No --> C

    F -- Yes --> G{Response status?}

    G -- APPROVED --> H[Append expert note: Approved\nAdd message to discussion]
    H --> I[Advance expert step\nrunNextExpertReview]

    G -- NEEDS_CLARIFICATION --> J{Has actual\nquestions?}
    J -- Yes --> K[Save pending questions\nBroadcast via WebSocket\nPause pipeline]
    K --> L[User submits answers\nvia /api/suggestions/id/expert-clarifications]
    L --> M[Continue conversation\nwith user answers]
    M --> A

    J -- No --> H

    G -- CHANGES_PROPOSED --> N[Pick change reviewer]
    N --> O{Proposing expert type?}
    O -- "Frontend, UX, QA" --> P[Reviewer = Product Manager]
    O -- "All others" --> Q[Reviewer = Software Architect]

    P --> R[ClaudeService.reviewExpertFeedback\nEvaluate proposed changes]
    Q --> R

    R --> S[handleReviewerResponse]
    S --> T{Reviewer says\napply = true?}

    T -- Yes --> U[Apply revised plan + tasks\nMark planChanged = true\nTrack changed domain]
    U --> I

    T -- No --> V[Note recommendations\nPlan remains unchanged]
    V --> I
```

---

## Sequential Expert Execution Detail

Each expert runs one at a time. After an expert completes, its response is processed before moving to the next.

```mermaid
sequenceDiagram
    participant SS as SuggestionService
    participant CS as ClaudeService
    participant WS as WebSocket
    participant DB as Database

    loop For each expert (1 through 11)
        SS->>DB: Load suggestion + plan + prior notes
        SS->>WS: Broadcast "Expert N is reviewing..."

        SS->>CS: expertReview(Expert N)
        CS-->>SS: Expert N response

        SS->>SS: handleExpertReviewResponse
        alt APPROVED
            SS->>DB: Append note, advance step
            SS->>WS: Broadcast expert_note
        else CHANGES_PROPOSED
            SS->>CS: reviewExpertFeedback (reviewer)
            CS-->>SS: Reviewer decision
            alt Apply changes
                SS->>DB: Update plan, mark planChanged
            else Reject changes
                SS->>DB: Note only, plan unchanged
            end
            SS->>DB: Advance step
        else NEEDS_CLARIFICATION
            SS->>DB: Save questions
            SS->>WS: Broadcast expert_clarification_questions
            Note over SS: Pipeline paused — waiting for user
        end

        SS->>SS: runNextExpertReview
    end
```

---

## Round & Re-Review Logic

When experts propose changes that get accepted, the plan is modified. After all experts finish, affected domain experts re-review.

```mermaid
flowchart TD
    A[All 11 experts completed\nstep >= experts.length] --> B{planChanged == true?}

    B -- No --> C[All experts approved or\nchanges were rejected\nTransition to PLANNED]

    B -- Yes --> D{round < MAX_ROUNDS\nAND totalRounds < MAX_TOTAL?}

    D -- Yes --> E[Calculate affected domains\nfrom changedDomains list]
    E --> F[Compute domain ripple:\naffectedDomains includes\nchanged + coupled domains]
    F --> G[Filter experts whose domain\nis in affectedDomains]
    G --> H["Start targeted re-review\nround++, totalRounds++\nnotes=null, planChanged=false"]
    H --> I[runTargetedExpertRereviews\nRun affected experts sequentially]

    I --> J[Each expert reviews\nupdated plan]
    J --> K{Expert proposes\nmore changes?}

    K -- "Accepted changes" --> L[Mark planChanged = true\nTrack new changed domains]
    K -- No --> M[Continue to next\naffected expert]

    L --> M
    M --> N{More affected\nexperts?}
    N -- Yes --> J
    N -- No --> O[Set step = experts.length\nTrigger finalization check]
    O --> A

    D -- No --> P{totalRounds >= MAX_TOTAL?}
    P -- "Yes (hard cap: 3)" --> Q[Force-finalize\nLog warning about hard cap]
    P -- "No (per-cycle cap: 2)" --> R[Force-finalize\nLog per-cycle max reached]

    Q --> C
    R --> C
```

### Constants

| Constant | Value | Purpose |
|---|---|---|
| `MIN_APPROVALS_FOR_EARLY_EXIT` | 3 | Minimum consecutive approvals (with no plan changes) before skipping remaining experts |
| `MAX_EXPERT_REVIEW_ROUNDS` | 2 | Maximum rounds within a single review cycle |
| `MAX_TOTAL_EXPERT_REVIEW_ROUNDS` | 3 | Hard cap across all cycles (including user-guided restarts) |

---

## User Clarification During Expert Review

When an expert needs more information from the user, the pipeline pauses and waits.

```mermaid
sequenceDiagram
    participant Expert as AI Expert
    participant SS as SuggestionService
    participant WS as WebSocket
    participant UI as Browser UI
    participant User as User
    participant API as REST API

    Expert->>SS: Response: NEEDS_CLARIFICATION\nwith questions[]
    SS->>SS: Save pendingClarificationQuestions
    SS->>WS: expert_clarification_questions event
    WS->>UI: Show clarification wizard
    Note over SS: Pipeline paused

    User->>UI: Fills in answers
    UI->>API: POST /api/suggestions/{id}/expert-clarifications
    API->>SS: handleExpertClarificationAnswers

    alt Expert exists for current step
        SS->>SS: Format answers as prompt
        SS->>Expert: continueConversation with answers
        Expert-->>SS: Updated review response
        SS->>SS: handleExpertReviewResponse\n(pipeline resumes)
    else Step past all experts (max-rounds clarification)
        alt totalRounds >= MAX_TOTAL
            SS->>SS: Force-finalize to PLANNED
        else Under hard cap
            SS->>SS: Reset pipeline\nstep=0, round=1\nAppend user guidance to notes
            SS->>SS: runNextExpertReview\n(full restart with guidance)
        end
    end
```

---

## WebSocket Events

Real-time updates are pushed to connected browsers throughout the review process.

```mermaid
graph LR
    subgraph "Server → Client Events"
        A[expert_review_status] --> A1["Current step, total steps,\nround, expert list with statuses\n(pending/in_progress/completed)"]
        B[expert_note] --> B1["Expert name + review note\n(shown as it completes)"]
        C[expert_clarification_questions] --> C1["Question list + expert name\n(triggers clarification wizard)"]
        D[progress] --> D1["Streaming progress text\nfrom Claude CLI"]
    end

    subgraph "Client → Server"
        E["POST /expert-clarifications"] --> E1["Question/answer pairs\nfrom user"]
    end

    subgraph "Status Query"
        F["GET /expert-review-status"] --> F1["Snapshot of current\nreview pipeline state"]
        G["GET /review-summary"] --> G1["Parsed summary of all\nexpert verdicts"]
    end
```

---

## Change Review Gate

When an expert proposes changes (`CHANGES_PROPOSED`), a second expert acts as reviewer to decide whether the changes should be applied.

```mermaid
flowchart LR
    A[Expert proposes\nCHANGES_PROPOSED] --> B{Who is the\nproposing expert?}

    B -- "Frontend Engineer\nUX Expert\nQA Engineer" --> C[Reviewer:\nProduct Manager]

    B -- "Software Architect\nSecurity Engineer\nInfrastructure Engineer\nData Analyst\nPerformance Engineer\nDevOps Engineer\nSoftware Engineer" --> D[Reviewer:\nSoftware Architect]

    C --> E[reviewExpertFeedback]
    D --> E

    E --> F{apply == true?}

    F -- Yes --> G["Update plan & tasks\nSet planChanged = true\nTrack domain"]
    F -- No --> H["Note recommendations\nPlan unchanged"]
```

### Round-Aware Reviewer Behavior

In re-review rounds (`round > 1`), the reviewer's acceptance bar is raised: only changes fixing **critical regressions** introduced by recent plan updates are approved. All other changes are rejected to ensure convergence.

---

## Suggestion Execution Concurrency

After admin approval, suggestion execution is gated by a configurable concurrency limit (`maxConcurrentSuggestions`, default **1**). This ensures only N suggestions run tasks at a time.

```mermaid
flowchart TD
    A[Admin approves suggestion] --> B[approveSuggestion\nSet status = APPROVED]
    B --> C{Active suggestions\n< maxConcurrentSuggestions?}

    C -- Yes --> D[Set status = IN_PROGRESS\nClone repo, create branch\nStart executing tasks]

    C -- No --> E[Stay APPROVED\nPhase: Queued — waiting for a slot]

    D --> F[Tasks execute sequentially...]
    F --> G[Suggestion exits active pipeline\nDEV_COMPLETE / DENIED / etc.]

    G --> H[tryStartNextQueuedSuggestion]
    H --> I{Any APPROVED\nsuggestions queued?}
    I -- Yes --> J[Pick oldest approved suggestion]
    J --> C
    I -- No --> K[Nothing to start]

    E -.->|Slot freed later| H
```

### How it works

- **Active suggestions** are counted as those with status `IN_PROGRESS` or `TESTING`
- When a suggestion is approved, the system checks if the active count is below the limit
- If at capacity, the suggestion stays `APPROVED` with a "queued" message and phase
- When any suggestion exits the active pipeline (dev complete, denied, failed), `tryStartNextQueuedSuggestion()` picks the oldest queued `APPROVED` suggestion and starts it
- On startup, `IN_PROGRESS`/`TESTING` suggestions resume first, then `APPROVED` suggestions are started respecting the limit

### Configuration

The limit is configurable in the **Settings** page under **Max Concurrent Suggestions** (default: 1). It is stored in `site_settings.max_concurrent_suggestions`.
