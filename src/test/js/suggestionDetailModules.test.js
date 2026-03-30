/**
 * Tests for tasks.js, expertReview.js, clarification.js, and suggestionDetail.js modules.
 * Functions are defined inline to match the project's test pattern
 * (Jest jsdom environment does not support native ES module imports).
 */

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

function makeState(overrides = {}) {
    return {
        loggedIn: false,
        username: '',
        role: '',
        permissions: [],
        currentSuggestion: null,
        currentStatus: null,
        settings: { allowVoting: false },
        tasks: [],
        taskTimer: null,
        expertReview: {
            currentStep: -1,
            totalSteps: 0,
            experts: [],
            active: false,
            notes: [],
            round: 1
        },
        expertClarification: {
            questions: [],
            answers: [],
            currentIndex: 0,
            active: false,
            expertName: ''
        },
        clarification: {
            questions: [],
            answers: [],
            currentIndex: 0,
            active: false
        },
        approvalPendingCount: 0,
        executionQueue: { maxConcurrent: 1, activeCount: 0, queuedCount: 0, queued: [] },
        ...overrides,
    };
}

function makeEsc() {
    return function esc(s) {
        if (!s) return '';
        const d = document.createElement('div');
        d.textContent = s;
        return d.innerHTML;
    };
}

function makeFormatContent(escFn) {
    return function formatContent(s) {
        if (!s) return '';
        let html = escFn(s);
        html = html.replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>');
        return html;
    };
}

function makeTimeAgo() {
    return function timeAgo(iso) {
        if (!iso) return '';
        const d = new Date(iso);
        const now = new Date();
        const diff = Math.floor((now - d) / 1000);
        if (diff < 60) return 'just now';
        if (diff < 3600) return Math.floor(diff / 60) + 'm ago';
        if (diff < 86400) return Math.floor(diff / 3600) + 'h ago';
        return Math.floor(diff / 86400) + 'd ago';
    };
}

// ---------------------------------------------------------------------------
// tasks.js — renderTasks()
// ---------------------------------------------------------------------------

function makeRenderTasks(state, escFn) {
    return function renderTasks() {
        const container = document.getElementById('detailTasks');
        const listEl = document.getElementById('taskList');
        const tasks = state.tasks;
        if (!tasks || tasks.length === 0) {
            container.style.display = 'none';
            return;
        }
        container.style.display = '';

        const completed = tasks.filter(t => t.status === 'COMPLETED').length;
        const total = tasks.length;
        const pct = Math.round((completed / total) * 100);
        document.getElementById('taskProgress').textContent = `${completed}/${total} completed`;
        document.getElementById('taskProgressFill').style.width = pct + '%';

        const hasActiveTask = tasks.some(t => (t.status === 'IN_PROGRESS' || t.status === 'REVIEWING') && t.startedAt);
        if (hasActiveTask && !state.taskTimer) {
            state.taskTimer = setInterval(() => renderTasks(), 10000);
        } else if (!hasActiveTask && state.taskTimer) {
            clearInterval(state.taskTimer);
            state.taskTimer = null;
        }

        listEl.innerHTML = tasks.map(t => {
            const icons = { PENDING: '○', IN_PROGRESS: '◉', REVIEWING: '⟳', COMPLETED: '✓', FAILED: '✗' };
            const icon = icons[t.status] || '○';
            const statusClass = t.status.toLowerCase();
            const titleClass = t.status === 'COMPLETED' ? 'task-title completed' : 'task-title';
            const statusLabel = t.status === 'REVIEWING' ? ' — reviewing' : (t.status === 'IN_PROGRESS' ? ' — in progress' : '');
            const isActive = t.status === 'IN_PROGRESS' || t.status === 'REVIEWING';
            const activityDetail = t.statusDetail ? escFn(t.statusDetail) : '';
            return `<div class="task-item ${isActive ? 'task-active' : ''}" data-task-order="${t.taskOrder}">
                <div class="task-icon ${statusClass}">${icon}</div>
                <div class="task-body">
                    <div class="${titleClass}">${t.taskOrder}. ${escFn(t.displayTitle || t.title)}</div>
                    ${activityDetail && isActive ? `<div class="task-activity">${activityDetail}</div>` : ''}
                </div>
            </div>`;
        }).join('');
    };
}

function setupTasksDom() {
    document.body.innerHTML = `
        <div id="detailTasks" style="display:none">
            <span id="taskProgress"></span>
            <div id="taskProgressFill" style="width:0%"></div>
            <div id="taskList"></div>
        </div>
    `;
}

describe('renderTasks()', () => {
    beforeEach(() => {
        setupTasksDom();
        jest.useFakeTimers();
    });

    afterEach(() => {
        jest.useRealTimers();
    });

    test('hides container when tasks list is empty', () => {
        const state = makeState({ tasks: [] });
        const renderTasks = makeRenderTasks(state, makeEsc());
        renderTasks();
        expect(document.getElementById('detailTasks').style.display).toBe('none');
    });

    test('shows container when tasks are present', () => {
        const state = makeState({
            tasks: [{ taskOrder: 1, title: 'Task 1', status: 'PENDING' }]
        });
        const renderTasks = makeRenderTasks(state, makeEsc());
        renderTasks();
        expect(document.getElementById('detailTasks').style.display).toBe('');
    });

    test('shows correct progress text', () => {
        const state = makeState({
            tasks: [
                { taskOrder: 1, title: 'Task 1', status: 'COMPLETED' },
                { taskOrder: 2, title: 'Task 2', status: 'PENDING' }
            ]
        });
        const renderTasks = makeRenderTasks(state, makeEsc());
        renderTasks();
        expect(document.getElementById('taskProgress').textContent).toBe('1/2 completed');
    });

    test('sets progress bar width proportionally', () => {
        const state = makeState({
            tasks: [
                { taskOrder: 1, title: 'T1', status: 'COMPLETED' },
                { taskOrder: 2, title: 'T2', status: 'COMPLETED' },
                { taskOrder: 3, title: 'T3', status: 'PENDING' },
                { taskOrder: 4, title: 'T4', status: 'PENDING' }
            ]
        });
        const renderTasks = makeRenderTasks(state, makeEsc());
        renderTasks();
        expect(document.getElementById('taskProgressFill').style.width).toBe('50%');
    });

    test('renders task items with title', () => {
        const state = makeState({
            tasks: [{ taskOrder: 1, title: 'Fix the bug', status: 'PENDING' }]
        });
        const renderTasks = makeRenderTasks(state, makeEsc());
        renderTasks();
        expect(document.getElementById('taskList').innerHTML).toContain('Fix the bug');
    });

    test('uses displayTitle when available', () => {
        const state = makeState({
            tasks: [{ taskOrder: 1, title: 'raw title', displayTitle: 'Display Title', status: 'PENDING' }]
        });
        const renderTasks = makeRenderTasks(state, makeEsc());
        renderTasks();
        expect(document.getElementById('taskList').innerHTML).toContain('Display Title');
        expect(document.getElementById('taskList').innerHTML).not.toContain('raw title');
    });

    test('applies task-active class to IN_PROGRESS task', () => {
        const state = makeState({
            tasks: [{ taskOrder: 1, title: 'Active', status: 'IN_PROGRESS', startedAt: new Date().toISOString() }]
        });
        const renderTasks = makeRenderTasks(state, makeEsc());
        renderTasks();
        expect(document.getElementById('taskList').innerHTML).toContain('task-active');
    });

    test('starts a timer when an active task exists and no timer is running', () => {
        const state = makeState({
            tasks: [{ taskOrder: 1, title: 'T', status: 'IN_PROGRESS', startedAt: new Date().toISOString() }]
        });
        const renderTasks = makeRenderTasks(state, makeEsc());
        renderTasks();
        expect(state.taskTimer).not.toBeNull();
        clearInterval(state.taskTimer);
    });

    test('clears timer when no active tasks remain', () => {
        const fakeTimer = setInterval(() => {}, 10000);
        const state = makeState({
            tasks: [{ taskOrder: 1, title: 'T', status: 'COMPLETED' }],
            taskTimer: fakeTimer
        });
        const renderTasks = makeRenderTasks(state, makeEsc());
        renderTasks();
        expect(state.taskTimer).toBeNull();
    });

    test('escapes HTML in task titles', () => {
        const state = makeState({
            tasks: [{ taskOrder: 1, title: '<script>alert(1)</script>', status: 'PENDING' }]
        });
        const renderTasks = makeRenderTasks(state, makeEsc());
        renderTasks();
        expect(document.getElementById('taskList').innerHTML).not.toContain('<script>');
        expect(document.getElementById('taskList').innerHTML).toContain('&lt;script&gt;');
    });
});

// ---------------------------------------------------------------------------
// tasks.js — updateTask()
// ---------------------------------------------------------------------------

function makeUpdateTask(state, renderTasks) {
    return function updateTask(taskData) {
        const tasks = state.tasks;
        const idx = tasks.findIndex(t => t.taskOrder === taskData.taskOrder);
        const prevStatus = idx >= 0 ? tasks[idx].status : null;
        if (idx >= 0) {
            tasks[idx] = { ...tasks[idx], ...taskData };
        } else {
            tasks.push(taskData);
            tasks.sort((a, b) => a.taskOrder - b.taskOrder);
        }
        renderTasks();
    };
}

describe('updateTask()', () => {
    beforeEach(() => {
        setupTasksDom();
    });

    test('updates existing task by taskOrder', () => {
        const state = makeState({
            tasks: [{ taskOrder: 1, title: 'T1', status: 'PENDING' }]
        });
        const renderTasks = makeRenderTasks(state, makeEsc());
        const updateTask = makeUpdateTask(state, renderTasks);
        updateTask({ taskOrder: 1, status: 'IN_PROGRESS' });
        expect(state.tasks[0].status).toBe('IN_PROGRESS');
    });

    test('adds new task when taskOrder not found', () => {
        const state = makeState({ tasks: [] });
        const renderTasks = makeRenderTasks(state, makeEsc());
        const updateTask = makeUpdateTask(state, renderTasks);
        updateTask({ taskOrder: 1, title: 'New task', status: 'PENDING' });
        expect(state.tasks).toHaveLength(1);
        expect(state.tasks[0].title).toBe('New task');
    });

    test('sorts tasks by taskOrder after adding', () => {
        const state = makeState({
            tasks: [{ taskOrder: 3, title: 'T3', status: 'PENDING' }]
        });
        const renderTasks = makeRenderTasks(state, makeEsc());
        const updateTask = makeUpdateTask(state, renderTasks);
        updateTask({ taskOrder: 1, title: 'T1', status: 'PENDING' });
        expect(state.tasks[0].taskOrder).toBe(1);
        expect(state.tasks[1].taskOrder).toBe(3);
    });

    test('preserves existing task fields when merging', () => {
        const state = makeState({
            tasks: [{ taskOrder: 1, title: 'T1', status: 'PENDING', estimatedMinutes: 10 }]
        });
        const renderTasks = makeRenderTasks(state, makeEsc());
        const updateTask = makeUpdateTask(state, renderTasks);
        updateTask({ taskOrder: 1, status: 'COMPLETED' });
        expect(state.tasks[0].estimatedMinutes).toBe(10);
        expect(state.tasks[0].status).toBe('COMPLETED');
    });

    test('calls renderTasks after update', () => {
        const state = makeState({ tasks: [{ taskOrder: 1, title: 'T1', status: 'PENDING' }] });
        const renderTasksMock = jest.fn();
        const updateTask = makeUpdateTask(state, renderTasksMock);
        updateTask({ taskOrder: 1, status: 'COMPLETED' });
        expect(renderTasksMock).toHaveBeenCalledTimes(1);
    });
});

// ---------------------------------------------------------------------------
// expertReview.js — renderExpertReview()
// ---------------------------------------------------------------------------

function makeRenderExpertReview(state, escFn) {
    return function renderExpertReview() {
        const container = document.getElementById('detailExpertReview');
        const listEl = document.getElementById('expertList');
        const er = state.expertReview;

        if (!er.active || er.experts.length === 0) {
            container.style.display = 'none';
            return;
        }
        container.style.display = '';

        const completed = er.experts.filter(e => e.status === 'completed').length;
        const roundLabel = er.round > 1 ? ` (round ${er.round})` : '';
        document.getElementById('expertProgress').textContent =
            `${completed}/${er.totalSteps} reviewed${roundLabel}`;

        const notes = er.notes || [];
        listEl.innerHTML = er.experts.map(e => {
            const icons = { pending: '○', in_progress: '◉', completed: '✓' };
            const icon = icons[e.status] || '○';
            const statusClass = 'expert-' + e.status;
            const label = e.status === 'in_progress' ? ' — reviewing' : '';
            return `<div class="expert-item ${statusClass}">
                <div class="expert-icon">${icon}</div>
                <div class="expert-name">${escFn(e.name)}${label}</div>
            </div>`;
        }).join('');
    };
}

function setupExpertReviewDom() {
    document.body.innerHTML = `
        <div id="detailExpertReview" style="display:none">
            <span id="expertProgress"></span>
            <div id="expertList"></div>
        </div>
    `;
}

describe('renderExpertReview()', () => {
    beforeEach(() => {
        setupExpertReviewDom();
    });

    test('hides container when expert review is not active', () => {
        const state = makeState();
        const renderExpertReview = makeRenderExpertReview(state, makeEsc());
        renderExpertReview();
        expect(document.getElementById('detailExpertReview').style.display).toBe('none');
    });

    test('hides container when active but no experts', () => {
        const state = makeState({ expertReview: { active: true, experts: [], totalSteps: 0, notes: [], round: 1 } });
        const renderExpertReview = makeRenderExpertReview(state, makeEsc());
        renderExpertReview();
        expect(document.getElementById('detailExpertReview').style.display).toBe('none');
    });

    test('shows container when active with experts', () => {
        const state = makeState({
            expertReview: {
                active: true,
                experts: [{ name: 'Alice', status: 'pending' }],
                totalSteps: 1,
                notes: [],
                round: 1
            }
        });
        const renderExpertReview = makeRenderExpertReview(state, makeEsc());
        renderExpertReview();
        expect(document.getElementById('detailExpertReview').style.display).toBe('');
    });

    test('shows correct progress text', () => {
        const state = makeState({
            expertReview: {
                active: true,
                experts: [
                    { name: 'Alice', status: 'completed' },
                    { name: 'Bob', status: 'pending' }
                ],
                totalSteps: 2,
                notes: [],
                round: 1
            }
        });
        const renderExpertReview = makeRenderExpertReview(state, makeEsc());
        renderExpertReview();
        expect(document.getElementById('expertProgress').textContent).toBe('1/2 reviewed');
    });

    test('shows round label when round > 1', () => {
        const state = makeState({
            expertReview: {
                active: true,
                experts: [{ name: 'Alice', status: 'pending' }],
                totalSteps: 1,
                notes: [],
                round: 2
            }
        });
        const renderExpertReview = makeRenderExpertReview(state, makeEsc());
        renderExpertReview();
        expect(document.getElementById('expertProgress').textContent).toContain('round 2');
    });

    test('renders expert names', () => {
        const state = makeState({
            expertReview: {
                active: true,
                experts: [{ name: 'Dr. Smith', status: 'in_progress' }],
                totalSteps: 1,
                notes: [],
                round: 1
            }
        });
        const renderExpertReview = makeRenderExpertReview(state, makeEsc());
        renderExpertReview();
        expect(document.getElementById('expertList').innerHTML).toContain('Dr. Smith');
    });

    test('escapes HTML in expert names', () => {
        const state = makeState({
            expertReview: {
                active: true,
                experts: [{ name: '<script>xss</script>', status: 'pending' }],
                totalSteps: 1,
                notes: [],
                round: 1
            }
        });
        const renderExpertReview = makeRenderExpertReview(state, makeEsc());
        renderExpertReview();
        expect(document.getElementById('expertList').innerHTML).not.toContain('<script>');
        expect(document.getElementById('expertList').innerHTML).toContain('&lt;script&gt;');
    });
});

// ---------------------------------------------------------------------------
// expertReview.js — updateExpertReview()
// ---------------------------------------------------------------------------

function makeUpdateExpertReview(state, renderExpertReview) {
    return function updateExpertReview(data) {
        const prevNotes = state.expertReview.notes || [];
        const isNewRound = data.round && data.round !== state.expertReview.round && data.currentStep === 0;
        state.expertReview = {
            currentStep: data.currentStep,
            totalSteps: data.totalSteps,
            experts: data.experts,
            round: data.round || 1,
            active: true,
            notes: isNewRound ? [] : prevNotes
        };
        renderExpertReview();
    };
}

describe('updateExpertReview()', () => {
    beforeEach(() => {
        setupExpertReviewDom();
    });

    test('sets expertReview state from incoming data', () => {
        const state = makeState();
        const renderExpertReview = jest.fn();
        const updateExpertReview = makeUpdateExpertReview(state, renderExpertReview);
        updateExpertReview({
            currentStep: 1,
            totalSteps: 3,
            experts: [{ name: 'Alice', status: 'completed' }],
            round: 1
        });
        expect(state.expertReview.currentStep).toBe(1);
        expect(state.expertReview.totalSteps).toBe(3);
        expect(state.expertReview.active).toBe(true);
        expect(state.expertReview.experts).toHaveLength(1);
    });

    test('preserves existing notes when not a new round', () => {
        const state = makeState({
            expertReview: {
                active: true,
                experts: [],
                totalSteps: 0,
                notes: [{ expertName: 'Alice', note: 'Good work' }],
                round: 1,
                currentStep: 1
            }
        });
        const renderExpertReview = jest.fn();
        const updateExpertReview = makeUpdateExpertReview(state, renderExpertReview);
        updateExpertReview({ currentStep: 2, totalSteps: 3, experts: [], round: 1 });
        expect(state.expertReview.notes).toHaveLength(1);
    });

    test('clears notes when a new round starts', () => {
        const state = makeState({
            expertReview: {
                active: true,
                experts: [],
                totalSteps: 0,
                notes: [{ expertName: 'Alice', note: 'Feedback' }],
                round: 1,
                currentStep: 2
            }
        });
        const renderExpertReview = jest.fn();
        const updateExpertReview = makeUpdateExpertReview(state, renderExpertReview);
        updateExpertReview({ currentStep: 0, totalSteps: 3, experts: [], round: 2 });
        expect(state.expertReview.notes).toHaveLength(0);
    });

    test('calls renderExpertReview after updating state', () => {
        const state = makeState();
        const renderExpertReview = jest.fn();
        const updateExpertReview = makeUpdateExpertReview(state, renderExpertReview);
        updateExpertReview({ currentStep: 0, totalSteps: 1, experts: [], round: 1 });
        expect(renderExpertReview).toHaveBeenCalledTimes(1);
    });

    test('defaults round to 1 when not provided', () => {
        const state = makeState();
        const renderExpertReview = jest.fn();
        const updateExpertReview = makeUpdateExpertReview(state, renderExpertReview);
        updateExpertReview({ currentStep: 0, totalSteps: 1, experts: [] });
        expect(state.expertReview.round).toBe(1);
    });
});

// ---------------------------------------------------------------------------
// clarification.js — showClarificationWizard() / hideClarificationWizard()
// ---------------------------------------------------------------------------

function makeShowClarificationWizard(state, renderClarificationStep) {
    return function showClarificationWizard(questions) {
        const c = state.clarification;
        c.questions = questions;
        c.answers = questions.map(() => '');
        c.currentIndex = 0;
        c.active = true;

        document.getElementById('clarificationWizard').style.display = '';
        document.getElementById('replyBox').style.display = 'none';
        renderClarificationStep();
    };
}

function makeHideClarificationWizard(state) {
    return function hideClarificationWizard() {
        state.clarification.active = false;
        document.getElementById('clarificationWizard').style.display = 'none';
    };
}

function makeRenderClarificationStep(state) {
    return function renderClarificationStep() {
        const c = state.clarification;
        const total = c.questions.length;
        const idx = c.currentIndex;
        document.getElementById('clarificationStep').textContent = 'Question ' + (idx + 1);
        document.getElementById('clarificationTotal').textContent = 'of ' + total;
        document.getElementById('clarificationQuestionText').textContent = c.questions[idx];
        document.getElementById('clarificationAnswer').value = c.answers[idx] || '';
        const pct = ((idx + 1) / total) * 100;
        document.getElementById('clarificationProgressFill').style.width = pct + '%';
        document.getElementById('clarificationPrevBtn').style.display = idx > 0 ? '' : 'none';
        const isLast = idx === total - 1;
        document.getElementById('clarificationNextBtn').style.display = isLast ? 'none' : '';
        document.getElementById('clarificationSubmitBtn').style.display = isLast ? '' : 'none';
    };
}

function setupClarificationDom() {
    document.body.innerHTML = `
        <div id="clarificationWizard" style="display:none">
            <div class="clarification-header"><h4>Clarification Needed</h4></div>
            <span id="clarificationStep"></span>
            <span id="clarificationTotal"></span>
            <div id="clarificationProgressFill" style="width:0%"></div>
            <p id="clarificationQuestionText"></p>
            <textarea id="clarificationAnswer"></textarea>
            <button id="clarificationPrevBtn"></button>
            <button id="clarificationNextBtn"></button>
            <button id="clarificationSubmitBtn"></button>
        </div>
        <div id="replyBox"></div>
    `;
}

describe('showClarificationWizard()', () => {
    beforeEach(() => {
        setupClarificationDom();
    });

    test('shows wizard and hides reply box', () => {
        const state = makeState();
        const renderClarificationStep = makeRenderClarificationStep(state);
        const showClarificationWizard = makeShowClarificationWizard(state, renderClarificationStep);
        showClarificationWizard(['Question 1', 'Question 2']);
        expect(document.getElementById('clarificationWizard').style.display).toBe('');
        expect(document.getElementById('replyBox').style.display).toBe('none');
    });

    test('sets clarification state with questions and empty answers', () => {
        const state = makeState();
        const renderClarificationStep = makeRenderClarificationStep(state);
        const showClarificationWizard = makeShowClarificationWizard(state, renderClarificationStep);
        showClarificationWizard(['Q1', 'Q2', 'Q3']);
        expect(state.clarification.questions).toHaveLength(3);
        expect(state.clarification.answers).toHaveLength(3);
        expect(state.clarification.answers.every(a => a === '')).toBe(true);
        expect(state.clarification.currentIndex).toBe(0);
        expect(state.clarification.active).toBe(true);
    });

    test('renders first question immediately', () => {
        const state = makeState();
        const renderClarificationStep = makeRenderClarificationStep(state);
        const showClarificationWizard = makeShowClarificationWizard(state, renderClarificationStep);
        showClarificationWizard(['First question?']);
        expect(document.getElementById('clarificationQuestionText').textContent).toBe('First question?');
    });
});

describe('hideClarificationWizard()', () => {
    beforeEach(() => {
        setupClarificationDom();
    });

    test('hides wizard and sets active to false', () => {
        const state = makeState({ clarification: { active: true, questions: [], answers: [], currentIndex: 0 } });
        const hideClarificationWizard = makeHideClarificationWizard(state);
        document.getElementById('clarificationWizard').style.display = '';
        hideClarificationWizard();
        expect(document.getElementById('clarificationWizard').style.display).toBe('none');
        expect(state.clarification.active).toBe(false);
    });
});

// ---------------------------------------------------------------------------
// clarification.js — renderClarificationStep()
// ---------------------------------------------------------------------------

describe('renderClarificationStep()', () => {
    beforeEach(() => {
        setupClarificationDom();
    });

    test('renders current question text and step indicator', () => {
        const state = makeState({
            clarification: {
                questions: ['What is your goal?', 'Why now?'],
                answers: ['', ''],
                currentIndex: 0,
                active: true
            }
        });
        const renderClarificationStep = makeRenderClarificationStep(state);
        renderClarificationStep();
        expect(document.getElementById('clarificationQuestionText').textContent).toBe('What is your goal?');
        expect(document.getElementById('clarificationStep').textContent).toBe('Question 1');
        expect(document.getElementById('clarificationTotal').textContent).toBe('of 2');
    });

    test('hides prev button on first question', () => {
        const state = makeState({
            clarification: {
                questions: ['Q1', 'Q2'],
                answers: ['', ''],
                currentIndex: 0,
                active: true
            }
        });
        const renderClarificationStep = makeRenderClarificationStep(state);
        renderClarificationStep();
        expect(document.getElementById('clarificationPrevBtn').style.display).toBe('none');
    });

    test('shows prev button on second question', () => {
        const state = makeState({
            clarification: {
                questions: ['Q1', 'Q2'],
                answers: ['Answer to Q1', ''],
                currentIndex: 1,
                active: true
            }
        });
        const renderClarificationStep = makeRenderClarificationStep(state);
        renderClarificationStep();
        expect(document.getElementById('clarificationPrevBtn').style.display).toBe('');
    });

    test('shows submit button on last question', () => {
        const state = makeState({
            clarification: {
                questions: ['Only question'],
                answers: [''],
                currentIndex: 0,
                active: true
            }
        });
        const renderClarificationStep = makeRenderClarificationStep(state);
        renderClarificationStep();
        expect(document.getElementById('clarificationSubmitBtn').style.display).toBe('');
        expect(document.getElementById('clarificationNextBtn').style.display).toBe('none');
    });

    test('shows next button on non-last question', () => {
        const state = makeState({
            clarification: {
                questions: ['Q1', 'Q2'],
                answers: ['', ''],
                currentIndex: 0,
                active: true
            }
        });
        const renderClarificationStep = makeRenderClarificationStep(state);
        renderClarificationStep();
        expect(document.getElementById('clarificationNextBtn').style.display).toBe('');
        expect(document.getElementById('clarificationSubmitBtn').style.display).toBe('none');
    });

    test('sets progress bar width based on current index', () => {
        const state = makeState({
            clarification: {
                questions: ['Q1', 'Q2', 'Q3', 'Q4'],
                answers: ['', '', '', ''],
                currentIndex: 1,
                active: true
            }
        });
        const renderClarificationStep = makeRenderClarificationStep(state);
        renderClarificationStep();
        expect(document.getElementById('clarificationProgressFill').style.width).toBe('50%');
    });

    test('restores saved answer in textarea', () => {
        const state = makeState({
            clarification: {
                questions: ['Q1', 'Q2'],
                answers: ['Previous answer', ''],
                currentIndex: 0,
                active: true
            }
        });
        const renderClarificationStep = makeRenderClarificationStep(state);
        renderClarificationStep();
        expect(document.getElementById('clarificationAnswer').value).toBe('Previous answer');
    });
});

// ---------------------------------------------------------------------------
// clarification.js — nextClarification() / prevClarification()
// ---------------------------------------------------------------------------

function makeSaveClarificationAnswer(state) {
    return function saveClarificationAnswer() {
        const c = state.clarification;
        c.answers[c.currentIndex] = document.getElementById('clarificationAnswer').value.trim();
    };
}

function makeNextClarification(state, renderClarificationStep, renderExpertClarificationStep) {
    return function nextClarification() {
        if (state.expertClarification.active) {
            const c = state.expertClarification;
            c.answers[c.currentIndex] = document.getElementById('clarificationAnswer').value.trim();
            if (c.currentIndex < c.questions.length - 1) {
                c.currentIndex++;
                renderExpertClarificationStep();
            }
            return;
        }
        const c = state.clarification;
        c.answers[c.currentIndex] = document.getElementById('clarificationAnswer').value.trim();
        if (c.currentIndex < c.questions.length - 1) {
            c.currentIndex++;
            renderClarificationStep();
        }
    };
}

function makePrevClarification(state, renderClarificationStep, renderExpertClarificationStep) {
    return function prevClarification() {
        if (state.expertClarification.active) {
            const c = state.expertClarification;
            c.answers[c.currentIndex] = document.getElementById('clarificationAnswer').value.trim();
            if (c.currentIndex > 0) {
                c.currentIndex--;
                renderExpertClarificationStep();
            }
            return;
        }
        const c = state.clarification;
        c.answers[c.currentIndex] = document.getElementById('clarificationAnswer').value.trim();
        if (c.currentIndex > 0) {
            c.currentIndex--;
            renderClarificationStep();
        }
    };
}

describe('nextClarification()', () => {
    beforeEach(() => {
        setupClarificationDom();
    });

    test('advances to next question and saves current answer', () => {
        const state = makeState({
            clarification: {
                questions: ['Q1', 'Q2'],
                answers: ['', ''],
                currentIndex: 0,
                active: true
            }
        });
        document.getElementById('clarificationAnswer').value = 'My answer';
        const renderClarificationStep = makeRenderClarificationStep(state);
        const nextClarification = makeNextClarification(state, renderClarificationStep, jest.fn());
        nextClarification();
        expect(state.clarification.answers[0]).toBe('My answer');
        expect(state.clarification.currentIndex).toBe(1);
    });

    test('does not advance past the last question', () => {
        const state = makeState({
            clarification: {
                questions: ['Only Q'],
                answers: [''],
                currentIndex: 0,
                active: true
            }
        });
        document.getElementById('clarificationAnswer').value = 'answer';
        const renderClarificationStep = jest.fn();
        const nextClarification = makeNextClarification(state, renderClarificationStep, jest.fn());
        nextClarification();
        expect(state.clarification.currentIndex).toBe(0);
    });

    test('delegates to expert clarification when expert mode is active', () => {
        const state = makeState({
            expertClarification: {
                questions: ['EQ1', 'EQ2'],
                answers: ['', ''],
                currentIndex: 0,
                active: true,
                expertName: 'Alice'
            }
        });
        document.getElementById('clarificationAnswer').value = 'expert answer';
        const renderExpertStep = jest.fn();
        const nextClarification = makeNextClarification(state, jest.fn(), renderExpertStep);
        nextClarification();
        expect(state.expertClarification.answers[0]).toBe('expert answer');
        expect(state.expertClarification.currentIndex).toBe(1);
        expect(renderExpertStep).toHaveBeenCalled();
    });
});

describe('prevClarification()', () => {
    beforeEach(() => {
        setupClarificationDom();
    });

    test('goes back to previous question', () => {
        const state = makeState({
            clarification: {
                questions: ['Q1', 'Q2'],
                answers: ['Answer 1', ''],
                currentIndex: 1,
                active: true
            }
        });
        document.getElementById('clarificationAnswer').value = '';
        const renderClarificationStep = makeRenderClarificationStep(state);
        const prevClarification = makePrevClarification(state, renderClarificationStep, jest.fn());
        prevClarification();
        expect(state.clarification.currentIndex).toBe(0);
    });

    test('does not go before the first question', () => {
        const state = makeState({
            clarification: {
                questions: ['Q1', 'Q2'],
                answers: ['', ''],
                currentIndex: 0,
                active: true
            }
        });
        document.getElementById('clarificationAnswer').value = '';
        const renderClarificationStep = jest.fn();
        const prevClarification = makePrevClarification(state, renderClarificationStep, jest.fn());
        prevClarification();
        expect(state.clarification.currentIndex).toBe(0);
        expect(renderClarificationStep).not.toHaveBeenCalled();
    });
});

// ---------------------------------------------------------------------------
// suggestionDetail.js — renderMessage()
// ---------------------------------------------------------------------------

function makeRenderMessage(escFn, timeAgoFn, formatContentFn) {
    return function renderMessage(m) {
        return `
            <div class="message message-${m.senderType}">
                <div class="message-header">
                    <strong>${escFn(m.senderName || m.senderType)}</strong>
                    <span>${timeAgoFn(m.createdAt)}</span>
                </div>
                <div class="message-content">${formatContentFn(m.content)}</div>
            </div>
        `;
    };
}

describe('renderMessage()', () => {
    const esc = makeEsc();
    const timeAgo = makeTimeAgo();
    const formatContent = makeFormatContent(esc);

    test('renders sender name and message content', () => {
        const renderMessage = makeRenderMessage(esc, timeAgo, formatContent);
        const m = {
            senderType: 'USER',
            senderName: 'Alice',
            content: 'Hello world',
            createdAt: new Date(Date.now() - 60000).toISOString()
        };
        const html = renderMessage(m);
        expect(html).toContain('Alice');
        expect(html).toContain('Hello world');
        expect(html).toContain('message-USER');
    });

    test('falls back to senderType when senderName is missing', () => {
        const renderMessage = makeRenderMessage(esc, timeAgo, formatContent);
        const m = {
            senderType: 'SYSTEM',
            senderName: null,
            content: 'Status changed',
            createdAt: new Date().toISOString()
        };
        const html = renderMessage(m);
        expect(html).toContain('SYSTEM');
    });

    test('escapes HTML in sender name', () => {
        const renderMessage = makeRenderMessage(esc, timeAgo, formatContent);
        const m = {
            senderType: 'USER',
            senderName: '<script>xss</script>',
            content: 'test',
            createdAt: new Date().toISOString()
        };
        const html = renderMessage(m);
        expect(html).not.toContain('<script>');
        expect(html).toContain('&lt;script&gt;');
    });

    test('applies bold markdown formatting to content', () => {
        const renderMessage = makeRenderMessage(esc, timeAgo, formatContent);
        const m = {
            senderType: 'AI',
            senderName: 'AI',
            content: '**important** info',
            createdAt: new Date().toISOString()
        };
        const html = renderMessage(m);
        expect(html).toContain('<strong>important</strong>');
    });
});

// ---------------------------------------------------------------------------
// suggestionDetail.js — renderMessages()
// ---------------------------------------------------------------------------

function makeRenderMessages(renderMessage) {
    return function renderMessages(messages) {
        const container = document.getElementById('threadContainer');
        container.innerHTML = messages.map(m => renderMessage(m)).join('');
        container.scrollTop = container.scrollHeight;
    };
}

describe('renderMessages()', () => {
    beforeEach(() => {
        document.body.innerHTML = '<div id="threadContainer"></div>';
    });

    test('renders all messages into the container', () => {
        const esc = makeEsc();
        const timeAgo = makeTimeAgo();
        const formatContent = makeFormatContent(esc);
        const renderMessage = makeRenderMessage(esc, timeAgo, formatContent);
        const renderMessages = makeRenderMessages(renderMessage);
        const messages = [
            { senderType: 'USER', senderName: 'Alice', content: 'Hello', createdAt: new Date().toISOString() },
            { senderType: 'AI', senderName: 'Bot', content: 'World', createdAt: new Date().toISOString() }
        ];
        renderMessages(messages);
        const container = document.getElementById('threadContainer');
        expect(container.innerHTML).toContain('Alice');
        expect(container.innerHTML).toContain('Bot');
    });

    test('clears previous messages before rendering', () => {
        const esc = makeEsc();
        const timeAgo = makeTimeAgo();
        const formatContent = makeFormatContent(esc);
        const renderMessage = makeRenderMessage(esc, timeAgo, formatContent);
        const renderMessages = makeRenderMessages(renderMessage);
        document.getElementById('threadContainer').innerHTML = '<div>old content</div>';
        renderMessages([]);
        expect(document.getElementById('threadContainer').innerHTML).toBe('');
    });
});

// ---------------------------------------------------------------------------
// suggestionDetail.js — vote()
// ---------------------------------------------------------------------------

function makeVote(state, apiImpl) {
    return async function vote(value) {
        const id = state.currentSuggestion;
        const data = await apiImpl('/suggestions/' + id + '/vote', {
            method: 'POST',
            body: JSON.stringify({ value })
        });
        if (data.upVotes !== undefined) {
            document.getElementById('detailUpVotes').textContent = data.upVotes;
            document.getElementById('detailDownVotes').textContent = data.downVotes;
        }
        if (data.error) alert(data.error);
    };
}

describe('vote()', () => {
    beforeEach(() => {
        document.body.innerHTML = `
            <span id="detailUpVotes">0</span>
            <span id="detailDownVotes">0</span>
        `;
        window.alert = jest.fn();
    });

    test('updates vote counts in DOM from API response', async () => {
        const state = makeState({ currentSuggestion: 42 });
        const apiImpl = jest.fn().mockResolvedValue({ upVotes: 5, downVotes: 2 });
        const vote = makeVote(state, apiImpl);
        await vote(1);
        expect(document.getElementById('detailUpVotes').textContent).toBe('5');
        expect(document.getElementById('detailDownVotes').textContent).toBe('2');
    });

    test('calls API with correct path and vote value', async () => {
        const state = makeState({ currentSuggestion: 7 });
        const apiImpl = jest.fn().mockResolvedValue({ upVotes: 1, downVotes: 0 });
        const vote = makeVote(state, apiImpl);
        await vote(-1);
        expect(apiImpl).toHaveBeenCalledWith('/suggestions/7/vote', expect.objectContaining({
            method: 'POST',
            body: JSON.stringify({ value: -1 })
        }));
    });

    test('shows alert when API returns an error', async () => {
        const state = makeState({ currentSuggestion: 1 });
        const apiImpl = jest.fn().mockResolvedValue({ error: 'Voting closed' });
        const vote = makeVote(state, apiImpl);
        await vote(1);
        expect(window.alert).toHaveBeenCalledWith('Voting closed');
    });
});

// ---------------------------------------------------------------------------
// suggestionDetail.js — changePriority()
// ---------------------------------------------------------------------------

function makeChangePriority(state, apiImpl, showToast) {
    return async function changePriority(newPriority) {
        const id = state.currentSuggestion;
        if (!id) return;
        const data = await apiImpl('/suggestions/' + id + '/priority', {
            method: 'PATCH',
            body: JSON.stringify({ priority: newPriority })
        });
        if (data && data.error) {
            showToast(data.error);
            return;
        }
        const priorityLabel = data.priority || newPriority;
        const badge = document.getElementById('detailPriorityBadge');
        if (badge) {
            badge.textContent = priorityLabel;
            badge.className = 'priority-badge priority-' + priorityLabel;
        }
    };
}

describe('changePriority()', () => {
    beforeEach(() => {
        document.body.innerHTML = `
            <span id="detailPriorityBadge" class="priority-badge priority-MEDIUM">MEDIUM</span>
        `;
    });

    test('updates priority badge text and class', async () => {
        const state = makeState({ currentSuggestion: 1 });
        const apiImpl = jest.fn().mockResolvedValue({ priority: 'HIGH' });
        const showToast = jest.fn();
        const changePriority = makeChangePriority(state, apiImpl, showToast);
        await changePriority('HIGH');
        const badge = document.getElementById('detailPriorityBadge');
        expect(badge.textContent).toBe('HIGH');
        expect(badge.className).toBe('priority-badge priority-HIGH');
    });

    test('does nothing when no current suggestion', async () => {
        const state = makeState({ currentSuggestion: null });
        const apiImpl = jest.fn();
        const showToast = jest.fn();
        const changePriority = makeChangePriority(state, apiImpl, showToast);
        await changePriority('HIGH');
        expect(apiImpl).not.toHaveBeenCalled();
    });

    test('shows toast when API returns an error', async () => {
        const state = makeState({ currentSuggestion: 1 });
        const apiImpl = jest.fn().mockResolvedValue({ error: 'Not allowed' });
        const showToast = jest.fn();
        const changePriority = makeChangePriority(state, apiImpl, showToast);
        await changePriority('HIGH');
        expect(showToast).toHaveBeenCalledWith('Not allowed');
    });

    test('falls back to newPriority value when API does not return priority', async () => {
        const state = makeState({ currentSuggestion: 1 });
        const apiImpl = jest.fn().mockResolvedValue({});
        const showToast = jest.fn();
        const changePriority = makeChangePriority(state, apiImpl, showToast);
        await changePriority('LOW');
        const badge = document.getElementById('detailPriorityBadge');
        expect(badge.textContent).toBe('LOW');
    });
});
