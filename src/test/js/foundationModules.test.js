/**
 * Tests for the three foundation modules: state, utils, and api.
 * Functions are defined inline to match the project's test pattern.
 */

// ---------------------------------------------------------------------------
// state — default shape
// ---------------------------------------------------------------------------

function makeDefaultState() {
    return {
        loggedIn: false,
        username: '',
        role: '',
        setupRequired: false,
        permissions: [],
        currentSuggestion: null,
        currentStatus: null,
        settings: {},
        ws: null,
        notificationWs: null,
        notificationWsReconnectTimeout: null,
        recommendations: [],
        clarification: {
            questions: [],
            answers: [],
            currentIndex: 0,
            active: false
        },
        tasks: [],
        taskTimer: null,
        expertReview: {
            currentStep: -1,
            totalSteps: 0,
            experts: [],
            active: false,
            notes: []
        },
        expertClarification: {
            questions: [],
            answers: [],
            currentIndex: 0,
            active: false,
            expertName: ''
        },
        approvalPendingCount: 0,
        myDraftsMode: false,
        listFilters: {
            search: '',
            status: '',
            priority: '',
            sortBy: 'created',
            sortDir: 'desc'
        },
        searchDebounceTimer: null,
        executionQueue: { maxConcurrent: 1, activeCount: 0, queuedCount: 0, queued: [] }
    };
}

describe('state — default values', () => {
    let state;

    beforeEach(() => {
        state = makeDefaultState();
    });

    test('loggedIn starts false', () => {
        expect(state.loggedIn).toBe(false);
    });

    test('username and role start as empty strings', () => {
        expect(state.username).toBe('');
        expect(state.role).toBe('');
    });

    test('setupRequired starts false', () => {
        expect(state.setupRequired).toBe(false);
    });

    test('permissions starts as empty array', () => {
        expect(Array.isArray(state.permissions)).toBe(true);
        expect(state.permissions).toHaveLength(0);
    });

    test('currentSuggestion and currentStatus start null', () => {
        expect(state.currentSuggestion).toBeNull();
        expect(state.currentStatus).toBeNull();
    });

    test('clarification wizard starts inactive at index 0', () => {
        expect(state.clarification.active).toBe(false);
        expect(state.clarification.currentIndex).toBe(0);
        expect(state.clarification.questions).toHaveLength(0);
        expect(state.clarification.answers).toHaveLength(0);
    });

    test('expertReview starts inactive at step -1', () => {
        expect(state.expertReview.active).toBe(false);
        expect(state.expertReview.currentStep).toBe(-1);
        expect(state.expertReview.totalSteps).toBe(0);
        expect(state.expertReview.experts).toHaveLength(0);
        expect(state.expertReview.notes).toHaveLength(0);
    });

    test('expertClarification starts inactive at index 0 with empty expertName', () => {
        expect(state.expertClarification.active).toBe(false);
        expect(state.expertClarification.currentIndex).toBe(0);
        expect(state.expertClarification.expertName).toBe('');
    });

    test('listFilters defaults to created/desc sort', () => {
        expect(state.listFilters.sortBy).toBe('created');
        expect(state.listFilters.sortDir).toBe('desc');
        expect(state.listFilters.search).toBe('');
        expect(state.listFilters.status).toBe('');
        expect(state.listFilters.priority).toBe('');
    });

    test('executionQueue starts with maxConcurrent 1 and empty queue', () => {
        expect(state.executionQueue.maxConcurrent).toBe(1);
        expect(state.executionQueue.activeCount).toBe(0);
        expect(state.executionQueue.queuedCount).toBe(0);
        expect(state.executionQueue.queued).toHaveLength(0);
    });

    test('approvalPendingCount starts at 0', () => {
        expect(state.approvalPendingCount).toBe(0);
    });

    test('myDraftsMode starts false', () => {
        expect(state.myDraftsMode).toBe(false);
    });

    test('ws and notificationWs start null', () => {
        expect(state.ws).toBeNull();
        expect(state.notificationWs).toBeNull();
        expect(state.notificationWsReconnectTimeout).toBeNull();
    });

    test('recommendations starts as empty array', () => {
        expect(Array.isArray(state.recommendations)).toBe(true);
        expect(state.recommendations).toHaveLength(0);
    });

    test('tasks starts as empty array with null timer', () => {
        expect(Array.isArray(state.tasks)).toBe(true);
        expect(state.tasks).toHaveLength(0);
        expect(state.taskTimer).toBeNull();
    });

    test('state is a plain mutable object', () => {
        state.loggedIn = true;
        expect(state.loggedIn).toBe(true);
    });
});

// ---------------------------------------------------------------------------
// utils — esc(), formatContent(), timeAgo()
// ---------------------------------------------------------------------------

function esc(s) {
    if (!s) return '';
    const d = document.createElement('div');
    d.textContent = s;
    return d.innerHTML;
}

function formatContent(s) {
    if (!s) return '';
    let html = esc(s);
    html = html.replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>');
    return html;
}

function timeAgo(iso) {
    if (!iso) return '';
    const d = new Date(iso);
    const now = new Date();
    const diff = Math.floor((now - d) / 1000);
    if (diff < 60) return 'just now';
    if (diff < 3600) return Math.floor(diff / 60) + 'm ago';
    if (diff < 86400) return Math.floor(diff / 3600) + 'h ago';
    return Math.floor(diff / 86400) + 'd ago';
}

describe('esc()', () => {
    test('returns empty string for falsy input', () => {
        expect(esc('')).toBe('');
        expect(esc(null)).toBe('');
        expect(esc(undefined)).toBe('');
    });

    test('escapes HTML special characters', () => {
        expect(esc('<script>')).toBe('&lt;script&gt;');
        expect(esc('a & b')).toBe('a &amp; b');
        expect(esc('"quoted"')).toBe('"quoted"');
    });

    test('returns plain text unchanged', () => {
        expect(esc('hello world')).toBe('hello world');
    });

    test('escapes nested tags', () => {
        expect(esc('<b>bold</b>')).toBe('&lt;b&gt;bold&lt;/b&gt;');
    });
});

describe('formatContent()', () => {
    test('returns empty string for falsy input', () => {
        expect(formatContent('')).toBe('');
        expect(formatContent(null)).toBe('');
        expect(formatContent(undefined)).toBe('');
    });

    test('converts **text** to <strong>text</strong>', () => {
        expect(formatContent('**bold**')).toBe('<strong>bold</strong>');
    });

    test('escapes HTML before applying bold markdown', () => {
        expect(formatContent('<b>raw</b>')).toBe('&lt;b&gt;raw&lt;/b&gt;');
    });

    test('handles multiple bold markers in one string', () => {
        const result = formatContent('**a** and **b**');
        expect(result).toBe('<strong>a</strong> and <strong>b</strong>');
    });

    test('leaves plain text unchanged', () => {
        expect(formatContent('hello')).toBe('hello');
    });

    test('does not double-escape already escaped content', () => {
        // A raw & in input should be escaped by esc() first
        const result = formatContent('a & b');
        expect(result).toBe('a &amp; b');
    });
});

describe('timeAgo()', () => {
    test('returns empty string for falsy input', () => {
        expect(timeAgo('')).toBe('');
        expect(timeAgo(null)).toBe('');
        expect(timeAgo(undefined)).toBe('');
    });

    test('returns "just now" for a timestamp within the last minute', () => {
        const recent = new Date(Date.now() - 30 * 1000).toISOString();
        expect(timeAgo(recent)).toBe('just now');
    });

    test('returns minutes ago for timestamps 1–59 minutes old', () => {
        const fiveMinutesAgo = new Date(Date.now() - 5 * 60 * 1000).toISOString();
        expect(timeAgo(fiveMinutesAgo)).toBe('5m ago');

        const fiftyNineMinutesAgo = new Date(Date.now() - 59 * 60 * 1000).toISOString();
        expect(timeAgo(fiftyNineMinutesAgo)).toBe('59m ago');
    });

    test('returns hours ago for timestamps 1–23 hours old', () => {
        const twoHoursAgo = new Date(Date.now() - 2 * 3600 * 1000).toISOString();
        expect(timeAgo(twoHoursAgo)).toBe('2h ago');
    });

    test('returns days ago for timestamps >= 1 day old', () => {
        const threeDaysAgo = new Date(Date.now() - 3 * 86400 * 1000).toISOString();
        expect(timeAgo(threeDaysAgo)).toBe('3d ago');
    });
});

// ---------------------------------------------------------------------------
// api() — fetch wrapper
// ---------------------------------------------------------------------------

function makeApi(fetchImpl, stateOverride = {}) {
    const state = { ...stateOverride };

    async function api(path, opts = {}) {
        const res = await fetchImpl('/api' + path, {
            headers: { 'Content-Type': 'application/json', ...opts.headers },
            ...opts
        });
        if (!res.ok && res.status !== 400 && res.status !== 401 && res.status !== 403) {
            throw new Error('Request failed: ' + res.status);
        }
        return res.json();
    }

    return api;
}

function makeFetchResponse(status, body) {
    return {
        ok: status >= 200 && status < 300,
        status,
        json: () => Promise.resolve(body)
    };
}

describe('api()', () => {
    test('prepends /api to the path and calls fetch', async () => {
        const mockFetch = jest.fn().mockResolvedValue(makeFetchResponse(200, { ok: true }));
        const api = makeApi(mockFetch);

        await api('/auth/status');

        expect(mockFetch).toHaveBeenCalledWith('/api/auth/status', expect.any(Object));
    });

    test('includes Content-Type application/json header by default', async () => {
        const mockFetch = jest.fn().mockResolvedValue(makeFetchResponse(200, {}));
        const api = makeApi(mockFetch);

        await api('/test');

        const callArgs = mockFetch.mock.calls[0][1];
        expect(callArgs.headers['Content-Type']).toBe('application/json');
    });

    test('includes caller-provided headers in the request', async () => {
        const mockFetch = jest.fn().mockResolvedValue(makeFetchResponse(200, {}));
        const api = makeApi(mockFetch);

        await api('/test', { headers: { 'X-Custom': 'value' } });

        const callArgs = mockFetch.mock.calls[0][1];
        // opts.headers is spread into both the merged headers object and via ...opts,
        // so the caller header is present regardless
        expect(callArgs.headers['X-Custom']).toBe('value');
    });

    test('returns parsed JSON on success', async () => {
        const body = { id: 1, name: 'test' };
        const mockFetch = jest.fn().mockResolvedValue(makeFetchResponse(200, body));
        const api = makeApi(mockFetch);

        const result = await api('/items');
        expect(result).toEqual(body);
    });

    test('throws on 5xx responses', async () => {
        const mockFetch = jest.fn().mockResolvedValue(makeFetchResponse(500, {}));
        const api = makeApi(mockFetch);

        await expect(api('/fail')).rejects.toThrow('Request failed: 500');
    });

    test('does not throw on 400 (bad request)', async () => {
        const body = { error: 'bad input' };
        const mockFetch = jest.fn().mockResolvedValue(makeFetchResponse(400, body));
        const api = makeApi(mockFetch);

        const result = await api('/bad');
        expect(result).toEqual(body);
    });

    test('does not throw on 401 (unauthorized)', async () => {
        const body = { error: 'not logged in' };
        const mockFetch = jest.fn().mockResolvedValue(makeFetchResponse(401, body));
        const api = makeApi(mockFetch);

        const result = await api('/protected');
        expect(result).toEqual(body);
    });

    test('does not throw on 403 (forbidden)', async () => {
        const body = { error: 'forbidden' };
        const mockFetch = jest.fn().mockResolvedValue(makeFetchResponse(403, body));
        const api = makeApi(mockFetch);

        const result = await api('/admin');
        expect(result).toEqual(body);
    });

    test('passes method and body through opts', async () => {
        const mockFetch = jest.fn().mockResolvedValue(makeFetchResponse(200, {}));
        const api = makeApi(mockFetch);

        await api('/create', { method: 'POST', body: JSON.stringify({ title: 'hi' }) });

        const callArgs = mockFetch.mock.calls[0][1];
        expect(callArgs.method).toBe('POST');
        expect(callArgs.body).toBe(JSON.stringify({ title: 'hi' }));
    });
});
