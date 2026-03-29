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

    APPROVED --> IN_PROGRESS : Execution starts

    IN_PROGRESS --> TESTING : Tasks complete
    TESTING --> DEV_COMPLETE : Tests pass
    DEV_COMPLETE --> FINAL_REVIEW : PR created
    FINAL_REVIEW --> MERGED : PR merged

    DISCUSSING --> TIMED_OUT : Inactivity
    EXPERT_REVIEW --> TIMED_OUT : Inactivity
    DRAFT --> TIMED_OUT : Inactivity
```

---

## Expert Panel & Batch Structure

Experts are organized into four sequential batches. Experts within the same batch run **concurrently**; batches run **sequentially** so later experts can see earlier notes.

```mermaid
block-beta
    columns 4

    block:batch1:1
        columns 1
        b1["Batch 1\nArchitecture & Security"]
        sa["Software Architect"]
        se["Security Engineer"]
        ie["Infrastructure Engineer"]
    end

    block:batch2:1
        columns 1
        b2["Batch 2\nData, Performance & Ops"]
        da["Data Analyst"]
        pe["Performance Engineer"]
        de["DevOps Engineer"]
    end

    block:batch3:1
        columns 1
        b3["Batch 3\nImplementation, UI & UX"]
        swe["Software Engineer"]
        fe["Frontend Engineer"]
        ux["UX Expert"]
    end

    block:batch4:1
        columns 1
        b4["Batch 4\nProduct & QA"]
        pm["Product Manager"]
        qa["QA Engineer"]
    end

    style b1 fill:#4a90d9,color:#fff
    style b2 fill:#50b88e,color:#fff
    style b3 fill:#e6a23c,color:#fff
    style b4 fill:#d9534f,color:#fff
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

    F -- No --> G{At start of batch?\npositionInBatch == 0}
    G -- Yes --> H[runExpertBatch\nLaunch batch concurrently]
    G -- No --> I[runSingleExpertReview\nResume mid-batch after clarification]

    H --> J[All experts in batch call\nClaudeService.expertReview]
    J --> K[Wait for all to complete]
    K --> L[Process results sequentially\nhandleExpertReviewResponse]

    I --> M[Single expert calls\nClaudeService.expertReview]
    M --> L

    L --> N{Pipeline paused?\nClarification needed?}
    N -- Yes --> O[Wait for user answers\nvia WebSocket + API]
    O --> P[handleExpertClarificationAnswers]
    P --> L

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

## Batch Execution Detail

Within a batch, experts run in parallel. Results are processed sequentially after all complete.

```mermaid
sequenceDiagram
    participant SS as SuggestionService
    participant CS as ClaudeService
    participant WS as WebSocket
    participant DB as Database

    Note over SS: runExpertBatch(batchIndex)

    SS->>DB: Load suggestion + plan
    SS->>WS: Broadcast "experts reviewing..."

    par Concurrent expert reviews
        SS->>CS: expertReview(Expert A)
        SS->>CS: expertReview(Expert B)
        SS->>CS: expertReview(Expert C)
    end

    CS-->>SS: Expert A response
    CS-->>SS: Expert B response
    CS-->>SS: Expert C response

    Note over SS: Process results sequentially

    loop For each expert result
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
            Note over SS: Pipeline paused—waiting for user
        end
    end

    SS->>SS: runNextExpertReview (next batch)
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

### Round Limits

| Constant | Value | Purpose |
|---|---|---|
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
