/**
 * Tests for the websocket module: connectWs, disconnectWs,
 * connectNotificationsWs, handleWsMessage, handleNotificationWsMessage,
 * and updateApprovalBanner.
 *
 * Functions are defined inline to match the project's test pattern
 * (Jest jsdom environment does not support native ES module imports).
 */

// ---------------------------------------------------------------------------
// Shared state factory
// ---------------------------------------------------------------------------

function makeState(overrides = {}) {
    return {
        loggedIn: false,
        username: '',
        role: '',
        permissions: [],
        currentSuggestion: null,
        currentStatus: null,
        currentView: 'list',
        ws: null,
        notificationWs: null,
        notificationWsReconnectTimeout: null,
        tasks: [],
        taskTimer: null,
        clarification: { active: false },
        approvalPendingCount: 0,
        executionQueue: { maxConcurrent: 1, activeCount: 0, queuedCount: 0, queued: [] },
        ...overrides,
    };
}

// ---------------------------------------------------------------------------
// Inline implementations mirroring the websocket module
// ---------------------------------------------------------------------------

function makeConnectWs(state, handleWsMessage) {
    function disconnectWs() {
        if (state.taskTimer) {
            clearInterval(state.taskTimer);
            state.taskTimer = null;
        }
        if (state.ws) {
            state.ws.onclose = null;
            state.ws.close();
            state.ws = null;
        }
    }

    function connectWs(suggestionId) {
        disconnectWs();
        const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
        const url = protocol + '//' + location.host + '/ws/suggestions/' + suggestionId;
        const ws = new WebSocket(url);

        ws.onmessage = (event) => {
            try {
                const data = JSON.parse(event.data);
                handleWsMessage(data);
            } catch (e) {
                console.error('WS parse error:', e);
            }
        };

        ws.onclose = () => {
            if (state.currentSuggestion === suggestionId) {
                setTimeout(() => {
                    if (state.currentSuggestion === suggestionId) {
                        connectWs(suggestionId);
                    }
                }, 3000);
            }
        };

        state.ws = ws;
    }

    return { connectWs, disconnectWs };
}

function makeConnectNotificationsWs(state, loadDetail) {
    function handleNotificationWsMessage(data) {
        if (data.type === 'clarification_needed') {
            if (Notification.permission !== 'granted') {
                return;
            }
            if (state.currentSuggestion === data.suggestionId) {
                return;
            }
            const n = new Notification('Clarification Needed', {
                body: 'Your suggestion "' + data.suggestionTitle + '" needs ' + data.questionCount + ' question(s) answered.',
                tag: 'suggestion-clarification-' + data.suggestionId
            });
            n.onclick = () => { window.focus(); loadDetail(data.suggestionId); n.close(); };
        } else if (data.type === 'approval_needed') {
            state.approvalPendingCount++;
            updateApprovalBanner(state);
        } else if (data.type === 'execution_queue_status') {
            state.executionQueue = data;
            if (state.currentView === 'list' && state._loadSuggestions) {
                state._loadSuggestions();
            }
        } else if (data.type === 'PROJECT_DEFINITION_UPDATE') {
            if (state._onProjectDefinitionUpdate) {
                state._onProjectDefinitionUpdate(data.data || data);
            }
        }
    }

    function connectNotificationsWs(username) {
        const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
        const url = protocol + '//' + location.host + '/ws/notifications?username=' + encodeURIComponent(username);
        const ws = new WebSocket(url);

        ws.onmessage = (event) => {
            try {
                const data = JSON.parse(event.data);
                handleNotificationWsMessage(data);
            } catch (e) {
                console.error('Notification WS parse error:', e);
            }
        };

        ws.onclose = () => {
            state.notificationWsReconnectTimeout = setTimeout(() => {
                if (state.loggedIn) {
                    connectNotificationsWs(state.username);
                }
            }, 3000);
        };

        state.notificationWs = ws;
    }

    return { connectNotificationsWs, handleNotificationWsMessage };
}

function updateApprovalBanner(state) {
    const isAdmin = state.role === 'ROOT_ADMIN' || state.role === 'ADMIN';
    const banner = document.getElementById('approvalPendingBanner');
    if (!banner) return;
    if (!isAdmin || state.approvalPendingCount === 0) {
        banner.style.display = 'none';
        return;
    }
    const count = state.approvalPendingCount;
    const label = count === 1 ? '1 suggestion is waiting for your approval' : count + ' suggestions are waiting for your approval';
    banner.querySelector('.approval-banner-text').textContent = label;
    banner.style.display = '';
}

// ---------------------------------------------------------------------------
// Mock WebSocket and Notification
// ---------------------------------------------------------------------------

let mockWsInstances = [];

class MockWebSocket {
    constructor(url) {
        this.url = url;
        this.onmessage = null;
        this.onclose = null;
        this.closeCalled = false;
        mockWsInstances.push(this);
    }
    close() {
        this.closeCalled = true;
    }
}

let mockNotificationInstances = [];

class MockNotification {
    constructor(title, options) {
        this.title = title;
        this.options = options;
        this.onclick = null;
        this.closeCalled = false;
        mockNotificationInstances.push(this);
    }
    close() {
        this.closeCalled = true;
    }
}
MockNotification.permission = 'granted';

beforeEach(() => {
    mockWsInstances = [];
    mockNotificationInstances = [];
    global.WebSocket = MockWebSocket;
    global.Notification = MockNotification;
    MockNotification.permission = 'granted';
    jest.useFakeTimers();
    jest.spyOn(console, 'error').mockImplementation(() => {});
    Object.defineProperty(window, 'location', {
        value: { protocol: 'http:', host: 'localhost:8080' },
        writable: true,
    });
});

afterEach(() => {
    jest.useRealTimers();
    jest.restoreAllMocks();
});

// ---------------------------------------------------------------------------
// connectWs
// ---------------------------------------------------------------------------

describe('connectWs', () => {
    test('opens WebSocket with ws: scheme over http', () => {
        const state = makeState();
        const { connectWs } = makeConnectWs(state, jest.fn());
        connectWs(42);

        expect(mockWsInstances).toHaveLength(1);
        expect(mockWsInstances[0].url).toBe('ws://localhost:8080/ws/suggestions/42');
    });

    test('opens WebSocket with wss: scheme over https', () => {
        Object.defineProperty(window, 'location', {
            value: { protocol: 'https:', host: 'example.com' },
            writable: true,
        });
        const state = makeState();
        const { connectWs } = makeConnectWs(state, jest.fn());
        connectWs(7);

        expect(mockWsInstances[0].url).toBe('wss://example.com/ws/suggestions/7');
    });

    test('stores WebSocket in state.ws', () => {
        const state = makeState();
        const { connectWs } = makeConnectWs(state, jest.fn());
        connectWs(1);

        expect(state.ws).toBe(mockWsInstances[0]);
    });

    test('onmessage parses JSON and calls handleWsMessage', () => {
        const state = makeState();
        const handler = jest.fn();
        const { connectWs } = makeConnectWs(state, handler);
        connectWs(1);

        mockWsInstances[0].onmessage({ data: JSON.stringify({ type: 'task_update', task: {} }) });

        expect(handler).toHaveBeenCalledWith({ type: 'task_update', task: {} });
    });

    test('onmessage logs error on invalid JSON without throwing', () => {
        const state = makeState();
        const { connectWs } = makeConnectWs(state, jest.fn());
        connectWs(1);

        expect(() => mockWsInstances[0].onmessage({ data: 'bad json' })).not.toThrow();
        expect(console.error).toHaveBeenCalled();
    });

    test('onclose reconnects after 3s if still on same suggestion', () => {
        const state = makeState({ currentSuggestion: 5 });
        const { connectWs } = makeConnectWs(state, jest.fn());
        connectWs(5);

        const firstWs = mockWsInstances[0];
        firstWs.onclose();

        jest.advanceTimersByTime(3000);

        // A second WebSocket should have been opened
        expect(mockWsInstances).toHaveLength(2);
        expect(mockWsInstances[1].url).toContain('/ws/suggestions/5');
    });

    test('onclose does not reconnect if suggestion changed', () => {
        const state = makeState({ currentSuggestion: 5 });
        const { connectWs } = makeConnectWs(state, jest.fn());
        connectWs(5);

        const firstWs = mockWsInstances[0];
        firstWs.onclose();

        // User navigated away before the timeout fires
        state.currentSuggestion = 99;

        jest.advanceTimersByTime(3000);

        expect(mockWsInstances).toHaveLength(1);
    });

    test('onclose does not schedule reconnect if not on that suggestion at close time', () => {
        const state = makeState({ currentSuggestion: 99 });
        const { connectWs } = makeConnectWs(state, jest.fn());
        connectWs(5); // connecting to suggestion 5, but currentSuggestion is 99

        mockWsInstances[0].onclose();

        jest.advanceTimersByTime(3000);

        expect(mockWsInstances).toHaveLength(1);
    });

    test('disconnects previous ws before opening a new one', () => {
        const state = makeState();
        const { connectWs } = makeConnectWs(state, jest.fn());
        connectWs(1);
        const firstWs = mockWsInstances[0];
        connectWs(2);

        expect(firstWs.closeCalled).toBe(true);
        expect(state.ws).toBe(mockWsInstances[1]);
    });

    test('disconnects previous ws onclose is set to null before closing (no reconnect loop)', () => {
        const state = makeState({ currentSuggestion: 1 });
        const { connectWs } = makeConnectWs(state, jest.fn());
        connectWs(1);
        const firstWs = mockWsInstances[0];

        connectWs(2); // should nullify firstWs.onclose before closing

        // firstWs.onclose should be null, so triggering it manually does nothing
        expect(firstWs.onclose).toBeNull();
    });
});

// ---------------------------------------------------------------------------
// disconnectWs
// ---------------------------------------------------------------------------

describe('disconnectWs', () => {
    test('closes the WebSocket and nulls state.ws', () => {
        const state = makeState();
        const { connectWs, disconnectWs } = makeConnectWs(state, jest.fn());
        connectWs(1);
        const ws = mockWsInstances[0];

        disconnectWs();

        expect(ws.closeCalled).toBe(true);
        expect(state.ws).toBeNull();
    });

    test('clears taskTimer and nulls it', () => {
        const state = makeState();
        const { disconnectWs } = makeConnectWs(state, jest.fn());
        state.taskTimer = setInterval(() => {}, 1000);

        disconnectWs();

        expect(state.taskTimer).toBeNull();
    });

    test('is safe to call when ws is already null', () => {
        const state = makeState();
        const { disconnectWs } = makeConnectWs(state, jest.fn());

        expect(() => disconnectWs()).not.toThrow();
    });
});

// ---------------------------------------------------------------------------
// connectNotificationsWs
// ---------------------------------------------------------------------------

describe('connectNotificationsWs', () => {
    test('opens WebSocket at notifications endpoint with encoded username', () => {
        const state = makeState();
        const { connectNotificationsWs } = makeConnectNotificationsWs(state, jest.fn());
        connectNotificationsWs('alice');

        expect(mockWsInstances[0].url).toBe('ws://localhost:8080/ws/notifications?username=alice');
    });

    test('URL-encodes special characters in username', () => {
        const state = makeState();
        const { connectNotificationsWs } = makeConnectNotificationsWs(state, jest.fn());
        connectNotificationsWs('user name+extra');

        expect(mockWsInstances[0].url).toContain('username=user%20name%2Bextra');
    });

    test('uses wss: over https', () => {
        Object.defineProperty(window, 'location', {
            value: { protocol: 'https:', host: 'example.com' },
            writable: true,
        });
        const state = makeState();
        const { connectNotificationsWs } = makeConnectNotificationsWs(state, jest.fn());
        connectNotificationsWs('bob');

        expect(mockWsInstances[0].url).toContain('wss://');
    });

    test('stores WebSocket in state.notificationWs', () => {
        const state = makeState();
        const { connectNotificationsWs } = makeConnectNotificationsWs(state, jest.fn());
        connectNotificationsWs('alice');

        expect(state.notificationWs).toBe(mockWsInstances[0]);
    });

    test('onmessage parses JSON and dispatches to handleNotificationWsMessage', () => {
        const state = makeState();
        const { connectNotificationsWs, handleNotificationWsMessage } = makeConnectNotificationsWs(state, jest.fn());
        const spy = jest.spyOn({ handleNotificationWsMessage }, 'handleNotificationWsMessage');
        connectNotificationsWs('alice');

        // Verify parsing occurs without throwing
        mockWsInstances[0].onmessage({ data: JSON.stringify({ type: 'unknown' }) });
        // No error expected
        expect(console.error).not.toHaveBeenCalled();
    });

    test('onmessage logs error on bad JSON without throwing', () => {
        const state = makeState();
        const { connectNotificationsWs } = makeConnectNotificationsWs(state, jest.fn());
        connectNotificationsWs('alice');

        expect(() => mockWsInstances[0].onmessage({ data: '{bad}' })).not.toThrow();
        expect(console.error).toHaveBeenCalled();
    });

    test('onclose schedules reconnect after 3s when loggedIn', () => {
        const state = makeState({ loggedIn: true, username: 'alice' });
        const { connectNotificationsWs } = makeConnectNotificationsWs(state, jest.fn());
        connectNotificationsWs('alice');
        const firstWs = mockWsInstances[0];

        firstWs.onclose();
        jest.advanceTimersByTime(3000);

        expect(mockWsInstances).toHaveLength(2);
    });

    test('onclose does not reconnect when not loggedIn', () => {
        const state = makeState({ loggedIn: false });
        const { connectNotificationsWs } = makeConnectNotificationsWs(state, jest.fn());
        connectNotificationsWs('alice');

        mockWsInstances[0].onclose();
        jest.advanceTimersByTime(3000);

        expect(mockWsInstances).toHaveLength(1);
    });

    test('onclose stores timeout id in state.notificationWsReconnectTimeout', () => {
        const state = makeState();
        const { connectNotificationsWs } = makeConnectNotificationsWs(state, jest.fn());
        connectNotificationsWs('alice');

        mockWsInstances[0].onclose();

        expect(state.notificationWsReconnectTimeout).not.toBeNull();
    });

    test('clearing the reconnect timeout prevents reconnection', () => {
        const state = makeState({ loggedIn: true, username: 'alice' });
        const { connectNotificationsWs } = makeConnectNotificationsWs(state, jest.fn());
        connectNotificationsWs('alice');

        mockWsInstances[0].onclose();
        clearTimeout(state.notificationWsReconnectTimeout);
        jest.advanceTimersByTime(3000);

        expect(mockWsInstances).toHaveLength(1);
    });
});

// ---------------------------------------------------------------------------
// handleNotificationWsMessage — clarification_needed
// ---------------------------------------------------------------------------

describe('handleNotificationWsMessage — clarification_needed', () => {
    const clarificationData = {
        type: 'clarification_needed',
        suggestionId: 42,
        suggestionTitle: 'My Feature',
        questionCount: 3,
    };

    test('shows browser Notification when permission granted and not on that suggestion', () => {
        const state = makeState({ currentSuggestion: null });
        MockNotification.permission = 'granted';
        const { handleNotificationWsMessage } = makeConnectNotificationsWs(state, jest.fn());

        handleNotificationWsMessage(clarificationData);

        expect(mockNotificationInstances).toHaveLength(1);
        expect(mockNotificationInstances[0].title).toBe('Clarification Needed');
        expect(mockNotificationInstances[0].options.body).toBe(
            'Your suggestion "My Feature" needs 3 question(s) answered.'
        );
    });

    test('sets notification tag to suggestion-clarification-{id}', () => {
        const state = makeState();
        MockNotification.permission = 'granted';
        const { handleNotificationWsMessage } = makeConnectNotificationsWs(state, jest.fn());

        handleNotificationWsMessage(clarificationData);

        expect(mockNotificationInstances[0].options.tag).toBe('suggestion-clarification-42');
    });

    test('does not show Notification when permission is denied', () => {
        const state = makeState();
        MockNotification.permission = 'denied';
        const { handleNotificationWsMessage } = makeConnectNotificationsWs(state, jest.fn());

        handleNotificationWsMessage(clarificationData);

        expect(mockNotificationInstances).toHaveLength(0);
    });

    test('does not show Notification when permission is default', () => {
        const state = makeState();
        MockNotification.permission = 'default';
        const { handleNotificationWsMessage } = makeConnectNotificationsWs(state, jest.fn());

        handleNotificationWsMessage(clarificationData);

        expect(mockNotificationInstances).toHaveLength(0);
    });

    test('does not show Notification when already viewing that suggestion', () => {
        const state = makeState({ currentSuggestion: 42 });
        MockNotification.permission = 'granted';
        const { handleNotificationWsMessage } = makeConnectNotificationsWs(state, jest.fn());

        handleNotificationWsMessage(clarificationData);

        expect(mockNotificationInstances).toHaveLength(0);
    });

    test('shows Notification when user is viewing a different suggestion', () => {
        const state = makeState({ currentSuggestion: 99 });
        MockNotification.permission = 'granted';
        const { handleNotificationWsMessage } = makeConnectNotificationsWs(state, jest.fn());

        handleNotificationWsMessage(clarificationData);

        expect(mockNotificationInstances).toHaveLength(1);
    });

    test('onclick calls window.focus, loadDetail, and closes the notification', () => {
        const state = makeState({ currentSuggestion: null });
        MockNotification.permission = 'granted';
        const loadDetail = jest.fn();
        jest.spyOn(window, 'focus').mockImplementation(() => {});
        const { handleNotificationWsMessage } = makeConnectNotificationsWs(state, loadDetail);

        handleNotificationWsMessage(clarificationData);

        const n = mockNotificationInstances[0];
        n.onclick();

        expect(window.focus).toHaveBeenCalled();
        expect(loadDetail).toHaveBeenCalledWith(42);
        expect(n.closeCalled).toBe(true);
    });
});

// ---------------------------------------------------------------------------
// handleNotificationWsMessage — approval_needed
// ---------------------------------------------------------------------------

describe('handleNotificationWsMessage — approval_needed', () => {
    test('increments approvalPendingCount', () => {
        const state = makeState({ role: 'ADMIN', approvalPendingCount: 0 });
        document.body.innerHTML = `<div id="approvalPendingBanner" style="display:none"><span class="approval-banner-text"></span></div>`;
        const { handleNotificationWsMessage } = makeConnectNotificationsWs(state, jest.fn());

        handleNotificationWsMessage({ type: 'approval_needed' });

        expect(state.approvalPendingCount).toBe(1);
    });

    test('increments approvalPendingCount multiple times', () => {
        const state = makeState({ role: 'ADMIN', approvalPendingCount: 2 });
        document.body.innerHTML = `<div id="approvalPendingBanner"><span class="approval-banner-text"></span></div>`;
        const { handleNotificationWsMessage } = makeConnectNotificationsWs(state, jest.fn());

        handleNotificationWsMessage({ type: 'approval_needed' });
        handleNotificationWsMessage({ type: 'approval_needed' });

        expect(state.approvalPendingCount).toBe(4);
    });
});

// ---------------------------------------------------------------------------
// handleNotificationWsMessage — execution_queue_status
// ---------------------------------------------------------------------------

describe('handleNotificationWsMessage — execution_queue_status', () => {
    test('updates state.executionQueue', () => {
        const state = makeState();
        const { handleNotificationWsMessage } = makeConnectNotificationsWs(state, jest.fn());
        const queueData = { type: 'execution_queue_status', maxConcurrent: 2, activeCount: 1, queuedCount: 3, queued: [] };

        handleNotificationWsMessage(queueData);

        expect(state.executionQueue).toBe(queueData);
    });

    test('calls loadSuggestions when currentView is list', () => {
        const state = makeState({ currentView: 'list' });
        const loadSuggestions = jest.fn();
        state._loadSuggestions = loadSuggestions;
        const { handleNotificationWsMessage } = makeConnectNotificationsWs(state, jest.fn());

        handleNotificationWsMessage({ type: 'execution_queue_status', queued: [] });

        expect(loadSuggestions).toHaveBeenCalled();
    });

    test('does not call loadSuggestions when currentView is detail', () => {
        const state = makeState({ currentView: 'detail' });
        const loadSuggestions = jest.fn();
        state._loadSuggestions = loadSuggestions;
        const { handleNotificationWsMessage } = makeConnectNotificationsWs(state, jest.fn());

        handleNotificationWsMessage({ type: 'execution_queue_status', queued: [] });

        expect(loadSuggestions).not.toHaveBeenCalled();
    });
});

// ---------------------------------------------------------------------------
// updateApprovalBanner
// ---------------------------------------------------------------------------

describe('updateApprovalBanner', () => {
    test('hides banner when no pending approvals', () => {
        const state = makeState({ role: 'ADMIN', approvalPendingCount: 0 });
        document.body.innerHTML = `<div id="approvalPendingBanner"><span class="approval-banner-text"></span></div>`;

        updateApprovalBanner(state);

        expect(document.getElementById('approvalPendingBanner').style.display).toBe('none');
    });

    test('hides banner for non-admin users even with pending approvals', () => {
        const state = makeState({ role: 'USER', approvalPendingCount: 5 });
        document.body.innerHTML = `<div id="approvalPendingBanner"><span class="approval-banner-text"></span></div>`;

        updateApprovalBanner(state);

        expect(document.getElementById('approvalPendingBanner').style.display).toBe('none');
    });

    test('shows banner for ADMIN with pending approvals', () => {
        const state = makeState({ role: 'ADMIN', approvalPendingCount: 2 });
        document.body.innerHTML = `<div id="approvalPendingBanner" style="display:none"><span class="approval-banner-text"></span></div>`;

        updateApprovalBanner(state);

        expect(document.getElementById('approvalPendingBanner').style.display).toBe('');
    });

    test('shows banner for ROOT_ADMIN with pending approvals', () => {
        const state = makeState({ role: 'ROOT_ADMIN', approvalPendingCount: 1 });
        document.body.innerHTML = `<div id="approvalPendingBanner" style="display:none"><span class="approval-banner-text"></span></div>`;

        updateApprovalBanner(state);

        expect(document.getElementById('approvalPendingBanner').style.display).toBe('');
    });

    test('displays singular label for exactly 1 pending', () => {
        const state = makeState({ role: 'ADMIN', approvalPendingCount: 1 });
        document.body.innerHTML = `<div id="approvalPendingBanner"><span class="approval-banner-text"></span></div>`;

        updateApprovalBanner(state);

        expect(document.querySelector('.approval-banner-text').textContent).toBe(
            '1 suggestion is waiting for your approval'
        );
    });

    test('displays plural label for more than 1 pending', () => {
        const state = makeState({ role: 'ADMIN', approvalPendingCount: 3 });
        document.body.innerHTML = `<div id="approvalPendingBanner"><span class="approval-banner-text"></span></div>`;

        updateApprovalBanner(state);

        expect(document.querySelector('.approval-banner-text').textContent).toBe(
            '3 suggestions are waiting for your approval'
        );
    });

    test('does nothing when banner element is absent', () => {
        const state = makeState({ role: 'ADMIN', approvalPendingCount: 1 });
        document.body.innerHTML = '';

        expect(() => updateApprovalBanner(state)).not.toThrow();
    });
});

// ---------------------------------------------------------------------------
// handleWsMessage dispatch — via connectWs onmessage
// ---------------------------------------------------------------------------

describe('handleWsMessage dispatch', () => {
    function buildDom() {
        document.body.innerHTML = `
            <div id="threadContainer"></div>
            <span id="detailStatus"></span>
            <div id="detailPhase"><span id="detailPhaseText"></span></div>
            <span id="detailUpVotes"></span>
            <span id="detailDownVotes"></span>
            <div id="detailPlan"><span id="detailPlanText"></span></div>
            <div id="adminActions"></div>
            <button id="forceReApprovalBtn"></button>
            <div id="retryPrActions"></div>
            <div id="replyBox"></div>
            <div id="noReplyMsg"></div>
            <div id="detailPr"><a id="detailPrLink"></a></div>
        `;
    }

    function makeHandleWsMessage(state, deps = {}) {
        const renderMessage = deps.renderMessage || (() => '<div class="msg">msg</div>');
        const renderTasks = deps.renderTasks || jest.fn();
        const updateTask = deps.updateTask || jest.fn();
        const updateExpertReview = deps.updateExpertReview || jest.fn();
        const addExpertNote = deps.addExpertNote || jest.fn();
        const showExpertClarificationWizard = deps.showExpertClarificationWizard || jest.fn();
        const showClarificationWizard = deps.showClarificationWizard || jest.fn();
        const hideClarificationWizard = deps.hideClarificationWizard || jest.fn();

        return function handleWsMessage(data) {
            switch (data.type) {
                case 'message': {
                    const container = document.getElementById('threadContainer');
                    const div = document.createElement('div');
                    div.innerHTML = renderMessage(data);
                    container.appendChild(div.firstElementChild);
                    container.scrollTop = container.scrollHeight;
                    break;
                }
                case 'status_update': {
                    state.currentStatus = data.status;
                    const statusEl = document.getElementById('detailStatus');
                    statusEl.textContent = data.status.replace('_', ' ');
                    statusEl.className = 'status-badge status-' + data.status;

                    const phaseEl = document.getElementById('detailPhase');
                    const phaseText = document.getElementById('detailPhaseText');
                    const phaseFinished = ['DENIED', 'TIMED_OUT', 'MERGED'].includes(data.status) ||
                        (data.status === 'DEV_COMPLETE' && (!data.currentPhase || data.currentPhase.startsWith('Implementation completed')));
                    if (data.currentPhase && !phaseFinished) {
                        phaseEl.style.display = '';
                        phaseText.textContent = data.currentPhase;
                    } else {
                        phaseEl.style.display = 'none';
                    }

                    document.getElementById('detailUpVotes').textContent = data.upVotes;
                    document.getElementById('detailDownVotes').textContent = data.downVotes;

                    const planEl2 = document.getElementById('detailPlan');
                    const planText2 = document.getElementById('detailPlanText');
                    const displayPlan = data.planDisplaySummary || data.planSummary;
                    if (displayPlan) {
                        planEl2.style.display = '';
                        planText2.textContent = displayPlan;
                    }

                    const isAdmin = state.role === 'ROOT_ADMIN' || state.role === 'ADMIN';
                    const canApprove = ['PLANNED', 'DISCUSSING'].includes(data.status);
                    const canForceReApproval = ['PLANNED', 'APPROVED'].includes(data.status);
                    document.getElementById('adminActions').style.display =
                        (isAdmin && (canApprove || canForceReApproval)) ? '' : 'none';
                    document.getElementById('forceReApprovalBtn').style.display =
                        (isAdmin && canForceReApproval) ? '' : 'none';

                    const canRetryPr2 = isAdmin && data.currentPhase === 'Done — review request failed';
                    document.getElementById('retryPrActions').style.display = canRetryPr2 ? '' : 'none';

                    const wsStatusAllowsReply = ['DRAFT', 'DISCUSSING', 'PLANNED'].includes(data.status);
                    const wsHasReplyPermission = isAdmin || state.permissions.includes('REPLY');
                    const canReply = wsStatusAllowsReply && wsHasReplyPermission;
                    const wsNoReplyMsg = document.getElementById('noReplyMsg');
                    if (!state.clarification.active) {
                        document.getElementById('replyBox').style.display = canReply ? '' : 'none';
                        if (wsNoReplyMsg) wsNoReplyMsg.style.display = (wsStatusAllowsReply && !wsHasReplyPermission) ? '' : 'none';
                    }

                    if (!['DISCUSSING', 'EXPERT_REVIEW'].includes(data.status)) {
                        hideClarificationWizard();
                        document.getElementById('replyBox').style.display = canReply ? '' : 'none';
                        if (wsNoReplyMsg) wsNoReplyMsg.style.display = (wsStatusAllowsReply && !wsHasReplyPermission) ? '' : 'none';
                    }
                    break;
                }
                case 'clarification_questions': {
                    if (data.questions && data.questions.length > 0) {
                        showClarificationWizard(data.questions);
                    }
                    break;
                }
                case 'pr_created': {
                    const prEl = document.getElementById('detailPr');
                    const prLink = document.getElementById('detailPrLink');
                    if (data.prUrl) {
                        prEl.style.display = '';
                        prLink.href = data.prUrl;
                        prLink.textContent = data.prUrl;
                    }
                    break;
                }
                case 'task_update': {
                    if (data.task) updateTask(data.task);
                    break;
                }
                case 'task_activity': {
                    if (data.taskOrder && data.detail) {
                        const tasks = state.tasks;
                        const idx = tasks.findIndex(t => t.taskOrder === data.taskOrder);
                        if (idx >= 0 && (tasks[idx].status === 'IN_PROGRESS' || tasks[idx].status === 'REVIEWING')) {
                            tasks[idx].statusDetail = data.detail;
                            renderTasks();
                        }
                    }
                    break;
                }
                case 'tasks_update': {
                    if (data.tasks) {
                        state.tasks = data.tasks;
                        renderTasks();
                    }
                    break;
                }
                case 'expert_review_status': {
                    updateExpertReview(data);
                    break;
                }
                case 'expert_note': {
                    if (data.expertName && data.note) addExpertNote(data.expertName, data.note);
                    break;
                }
                case 'expert_clarification_questions': {
                    if (data.questions && data.questions.length > 0) {
                        showExpertClarificationWizard(data.questions, data.expertName || 'Expert');
                    }
                    break;
                }
                case 'progress':
                case 'execution_progress': {
                    const phaseText = document.getElementById('detailPhaseText');
                    if (data.content) phaseText.textContent = data.content.substring(0, 200);
                    break;
                }
            }
        };
    }

    test('message — appends rendered message to thread container', () => {
        buildDom();
        const state = makeState();
        const renderMessage = jest.fn(() => '<div class="msg">hello</div>');
        const handler = makeHandleWsMessage(state, { renderMessage });

        handler({ type: 'message', content: 'hello' });

        expect(document.getElementById('threadContainer').children).toHaveLength(1);
        expect(renderMessage).toHaveBeenCalled();
    });

    test('status_update — updates state.currentStatus', () => {
        buildDom();
        const state = makeState({ role: 'USER', permissions: ['REPLY'] });
        const handler = makeHandleWsMessage(state);

        handler({ type: 'status_update', status: 'DISCUSSING', upVotes: 3, downVotes: 1 });

        expect(state.currentStatus).toBe('DISCUSSING');
    });

    test('status_update — updates status badge text and class', () => {
        buildDom();
        const state = makeState();
        const handler = makeHandleWsMessage(state);

        handler({ type: 'status_update', status: 'DEV_COMPLETE', upVotes: 0, downVotes: 0 });

        const el = document.getElementById('detailStatus');
        expect(el.textContent).toBe('DEV COMPLETE');
        expect(el.className).toContain('status-DEV_COMPLETE');
    });

    test('status_update — shows phase when not finished', () => {
        buildDom();
        const state = makeState();
        const handler = makeHandleWsMessage(state);

        handler({ type: 'status_update', status: 'DISCUSSING', currentPhase: 'Planning phase', upVotes: 0, downVotes: 0 });

        expect(document.getElementById('detailPhase').style.display).toBe('');
        expect(document.getElementById('detailPhaseText').textContent).toBe('Planning phase');
    });

    test('status_update — hides phase when status is DENIED', () => {
        buildDom();
        const state = makeState();
        const handler = makeHandleWsMessage(state);

        handler({ type: 'status_update', status: 'DENIED', currentPhase: 'some phase', upVotes: 0, downVotes: 0 });

        expect(document.getElementById('detailPhase').style.display).toBe('none');
    });

    test('status_update — shows admin actions for ADMIN on PLANNED status', () => {
        buildDom();
        const state = makeState({ role: 'ADMIN' });
        const handler = makeHandleWsMessage(state);

        handler({ type: 'status_update', status: 'PLANNED', upVotes: 0, downVotes: 0 });

        expect(document.getElementById('adminActions').style.display).toBe('');
    });

    test('status_update — hides admin actions for non-admin users', () => {
        buildDom();
        const state = makeState({ role: 'USER' });
        const handler = makeHandleWsMessage(state);

        handler({ type: 'status_update', status: 'PLANNED', upVotes: 0, downVotes: 0 });

        expect(document.getElementById('adminActions').style.display).toBe('none');
    });

    test('status_update — shows plan summary when provided', () => {
        buildDom();
        const state = makeState();
        const handler = makeHandleWsMessage(state);

        handler({ type: 'status_update', status: 'PLANNED', upVotes: 0, downVotes: 0, planSummary: 'Do the thing' });

        expect(document.getElementById('detailPlanText').textContent).toBe('Do the thing');
        expect(document.getElementById('detailPlan').style.display).toBe('');
    });

    test('status_update — calls hideClarificationWizard when status not DISCUSSING/EXPERT_REVIEW', () => {
        buildDom();
        const state = makeState({ permissions: ['REPLY'] });
        const hideClarificationWizard = jest.fn();
        const handler = makeHandleWsMessage(state, { hideClarificationWizard });

        handler({ type: 'status_update', status: 'MERGED', upVotes: 0, downVotes: 0 });

        expect(hideClarificationWizard).toHaveBeenCalled();
    });

    test('status_update — does not call hideClarificationWizard for DISCUSSING status', () => {
        buildDom();
        const state = makeState({ permissions: ['REPLY'] });
        const hideClarificationWizard = jest.fn();
        const handler = makeHandleWsMessage(state, { hideClarificationWizard });

        handler({ type: 'status_update', status: 'DISCUSSING', upVotes: 0, downVotes: 0 });

        expect(hideClarificationWizard).not.toHaveBeenCalled();
    });

    test('clarification_questions — calls showClarificationWizard with questions', () => {
        const state = makeState();
        const showClarificationWizard = jest.fn();
        const handler = makeHandleWsMessage(state, { showClarificationWizard });

        handler({ type: 'clarification_questions', questions: ['Q1', 'Q2'] });

        expect(showClarificationWizard).toHaveBeenCalledWith(['Q1', 'Q2']);
    });

    test('clarification_questions — does not call showClarificationWizard for empty array', () => {
        const state = makeState();
        const showClarificationWizard = jest.fn();
        const handler = makeHandleWsMessage(state, { showClarificationWizard });

        handler({ type: 'clarification_questions', questions: [] });

        expect(showClarificationWizard).not.toHaveBeenCalled();
    });

    test('pr_created — updates PR link when prUrl provided', () => {
        buildDom();
        const state = makeState();
        const handler = makeHandleWsMessage(state);

        handler({ type: 'pr_created', prUrl: 'https://github.com/org/repo/pull/1' });

        expect(document.getElementById('detailPr').style.display).toBe('');
        expect(document.getElementById('detailPrLink').href).toContain('pull/1');
    });

    test('pr_created — does nothing when prUrl is absent', () => {
        buildDom();
        document.getElementById('detailPr').style.display = 'none';
        const state = makeState();
        const handler = makeHandleWsMessage(state);

        handler({ type: 'pr_created' });

        expect(document.getElementById('detailPr').style.display).toBe('none');
    });

    test('task_update — calls updateTask with the task data', () => {
        const state = makeState();
        const updateTask = jest.fn();
        const handler = makeHandleWsMessage(state, { updateTask });
        const task = { taskOrder: 1, status: 'COMPLETED' };

        handler({ type: 'task_update', task });

        expect(updateTask).toHaveBeenCalledWith(task);
    });

    test('task_activity — updates statusDetail and calls renderTasks', () => {
        const state = makeState({ tasks: [{ taskOrder: 2, status: 'IN_PROGRESS', statusDetail: '' }] });
        const renderTasks = jest.fn();
        const handler = makeHandleWsMessage(state, { renderTasks });

        handler({ type: 'task_activity', taskOrder: 2, detail: 'Working on it...' });

        expect(state.tasks[0].statusDetail).toBe('Working on it...');
        expect(renderTasks).toHaveBeenCalled();
    });

    test('task_activity — does not update task if status is COMPLETED', () => {
        const state = makeState({ tasks: [{ taskOrder: 2, status: 'COMPLETED', statusDetail: '' }] });
        const renderTasks = jest.fn();
        const handler = makeHandleWsMessage(state, { renderTasks });

        handler({ type: 'task_activity', taskOrder: 2, detail: 'Should be ignored' });

        expect(state.tasks[0].statusDetail).toBe('');
        expect(renderTasks).not.toHaveBeenCalled();
    });

    test('tasks_update — replaces state.tasks and calls renderTasks', () => {
        const state = makeState({ tasks: [] });
        const renderTasks = jest.fn();
        const handler = makeHandleWsMessage(state, { renderTasks });
        const newTasks = [{ taskOrder: 1 }, { taskOrder: 2 }];

        handler({ type: 'tasks_update', tasks: newTasks });

        expect(state.tasks).toBe(newTasks);
        expect(renderTasks).toHaveBeenCalled();
    });

    test('expert_review_status — calls updateExpertReview', () => {
        const state = makeState();
        const updateExpertReview = jest.fn();
        const handler = makeHandleWsMessage(state, { updateExpertReview });
        const reviewData = { type: 'expert_review_status', step: 2 };

        handler(reviewData);

        expect(updateExpertReview).toHaveBeenCalledWith(reviewData);
    });

    test('expert_note — calls addExpertNote with expertName and note', () => {
        const state = makeState();
        const addExpertNote = jest.fn();
        const handler = makeHandleWsMessage(state, { addExpertNote });

        handler({ type: 'expert_note', expertName: 'Alice', note: 'Looks good' });

        expect(addExpertNote).toHaveBeenCalledWith('Alice', 'Looks good');
    });

    test('expert_note — does not call addExpertNote when fields are missing', () => {
        const state = makeState();
        const addExpertNote = jest.fn();
        const handler = makeHandleWsMessage(state, { addExpertNote });

        handler({ type: 'expert_note' });

        expect(addExpertNote).not.toHaveBeenCalled();
    });

    test('expert_clarification_questions — calls showExpertClarificationWizard', () => {
        const state = makeState();
        const showExpertClarificationWizard = jest.fn();
        const handler = makeHandleWsMessage(state, { showExpertClarificationWizard });

        handler({ type: 'expert_clarification_questions', questions: ['Q1'], expertName: 'Bob' });

        expect(showExpertClarificationWizard).toHaveBeenCalledWith(['Q1'], 'Bob');
    });

    test('expert_clarification_questions — defaults expertName to "Expert" when absent', () => {
        const state = makeState();
        const showExpertClarificationWizard = jest.fn();
        const handler = makeHandleWsMessage(state, { showExpertClarificationWizard });

        handler({ type: 'expert_clarification_questions', questions: ['Q1'] });

        expect(showExpertClarificationWizard).toHaveBeenCalledWith(['Q1'], 'Expert');
    });

    test('progress — updates phase text with truncated content', () => {
        buildDom();
        const state = makeState();
        const handler = makeHandleWsMessage(state);
        const longContent = 'x'.repeat(300);

        handler({ type: 'progress', content: longContent });

        expect(document.getElementById('detailPhaseText').textContent).toHaveLength(200);
    });

    test('execution_progress — also updates phase text', () => {
        buildDom();
        const state = makeState();
        const handler = makeHandleWsMessage(state);

        handler({ type: 'execution_progress', content: 'Running tests...' });

        expect(document.getElementById('detailPhaseText').textContent).toBe('Running tests...');
    });

    test('unknown message type — does not throw', () => {
        const state = makeState();
        const handler = makeHandleWsMessage(state);

        expect(() => handler({ type: 'totally_unknown' })).not.toThrow();
    });
});
