/**
 * Tests for suggestions.js module.
 * Functions are defined inline to match the project's test pattern
 * (Jest jsdom environment does not support native ES module imports).
 */

// ---------------------------------------------------------------------------
// Inline implementations (mirroring modules/suggestions.js)
// ---------------------------------------------------------------------------

function makeState(overrides = {}) {
    return {
        loggedIn: false,
        permissions: [],
        settings: {},
        listFilters: {
            search: '',
            status: '',
            priority: '',
            sortBy: 'created',
            sortDir: 'desc'
        },
        searchDebounceTimer: null,
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

function makeUpdateNewSuggestionBtn(state) {
    return function updateNewSuggestionBtn() {
        const btn = document.getElementById('newSuggestionBtn');
        if (!btn) return;
        const allowAnon = state.settings.allowAnonymousSuggestions;
        const hasPermission = state.permissions.includes('CREATE_SUGGESTIONS');
        btn.style.display = (allowAnon || hasPermission) ? '' : 'none';
    };
}

function makeRestoreFiltersFromUrl(state) {
    return function restoreFiltersFromUrl() {
        const params = new URLSearchParams(window.location.search);
        const search = params.get('search') || '';
        const status = params.get('status') || '';
        const priority = params.get('priority') || '';
        const sortBy = params.get('sortBy') || 'created';
        const sortDir = params.get('sortDir') || 'desc';
        state.listFilters = { search, status, priority, sortBy, sortDir };

        const searchEl = document.getElementById('searchInput');
        const statusEl = document.getElementById('statusFilter');
        const priorityEl = document.getElementById('priorityFilter');
        const sortByEl = document.getElementById('sortByFilter');
        const sortDirEl = document.getElementById('sortDirFilter');
        if (searchEl) searchEl.value = search;
        if (statusEl) statusEl.value = status;
        if (priorityEl) priorityEl.value = priority;
        if (sortByEl) sortByEl.value = sortBy;
        if (sortDirEl) sortDirEl.value = sortDir;
    };
}

function makeApplyFilters(state, loadSuggestions) {
    return function applyFilters() {
        const search = document.getElementById('searchInput')?.value ?? '';
        const status = document.getElementById('statusFilter')?.value ?? '';
        const priority = document.getElementById('priorityFilter')?.value ?? '';
        const sortBy = document.getElementById('sortByFilter')?.value ?? 'created';
        const sortDir = document.getElementById('sortDirFilter')?.value ?? 'desc';

        state.listFilters = { search, status, priority, sortBy, sortDir };

        const params = new URLSearchParams();
        if (search) params.set('search', search);
        if (status) params.set('status', status);
        if (priority) params.set('priority', priority);
        if (sortBy !== 'created') params.set('sortBy', sortBy);
        if (sortDir !== 'desc') params.set('sortDir', sortDir);
        const qs = params.toString();
        history.replaceState(null, '', qs ? '?' + qs : window.location.pathname);

        loadSuggestions();
    };
}

function makeOnSearchInput(state, applyFilters) {
    return function onSearchInput() {
        clearTimeout(state.searchDebounceTimer);
        state.searchDebounceTimer = setTimeout(() => applyFilters(), 300);
    };
}

function makeRenderSuggestionItem(state, esc, timeAgo) {
    return function renderSuggestionItem(s, settings, queueStatus) {
        const canQuickApprove = state.permissions.includes('APPROVE_DENY_SUGGESTIONS');
        const queueMap = {};
        (queueStatus.queued || []).forEach(q => { queueMap[q.id] = q.position; });

        const showApproveActions = canQuickApprove && ['PLANNED', 'DISCUSSING'].includes(s.status);
        const priorityLabel = s.priority || 'MEDIUM';
        const queuePos = queueMap[s.id];
        const queueInfo = (s.status === 'APPROVED' && queuePos) ?
            `<div style="font-size:0.8rem;color:var(--text-muted);margin-top:0.5rem">&#9201; Queue position: ${queuePos} of ${queueStatus.queuedCount} (${queueStatus.activeCount}/${queueStatus.maxConcurrent} slots in use)</div>` : '';
        return `
        <div class="card suggestion-item" data-suggestion-id="${s.id}" onclick="app.navigate('detail', ${s.id})">
            <div class="suggestion-header">
                <div>
                    <div class="suggestion-title">${esc(s.title)}</div>
                    <div class="suggestion-meta">
                        <span>by ${esc(s.authorName || 'Anonymous')}</span>
                        <span>${timeAgo(s.createdAt)}</span>
                        ${settings.allowVoting ? `<span>&#9650; ${s.upVotes} &#9660; ${s.downVotes}</span>` : ''}
                    </div>
                </div>
                <div style="display:flex;align-items:center;gap:0.4rem;flex-wrap:wrap">
                    <span class="priority-badge priority-${priorityLabel}">${priorityLabel}</span>
                    <span class="status-badge status-${s.status}">${s.status.replace('_', ' ')}</span>
                </div>
            </div>
            ${s.currentPhase ? `<div style="font-size:0.8rem;color:${['IN_PROGRESS','EXPERT_REVIEW'].includes(s.status) ? 'var(--primary)' : 'var(--text-muted)'};margin-top:0.5rem">${['IN_PROGRESS','EXPERT_REVIEW'].includes(s.status) ? '<span class="spinner" style="display:inline-block;width:12px;height:12px;margin-right:4px;vertical-align:middle"></span>' : ''}${esc(s.currentPhase)}</div>` : ''}
            ${queueInfo}
            ${showApproveActions ? `<div class="suggestion-quick-actions" onclick="event.stopPropagation()">
                <button class="btn btn-success btn-sm" onclick="app.approveSuggestion(${s.id})">Approve</button>
                <button class="btn btn-danger btn-sm" onclick="app.denySuggestion(${s.id})">Deny</button>
            </div>` : ''}
        </div>`;
    };
}

function makeLoadSuggestions(state, apiImpl, updateNewSuggestionBtn, restoreFiltersFromUrl, renderSuggestionItem) {
    return async function loadSuggestions() {
        const myDraftsBtn = document.getElementById('myDraftsBtn');
        if (myDraftsBtn) {
            myDraftsBtn.textContent = 'My Drafts';
        }
        restoreFiltersFromUrl();
        const list = document.getElementById('suggestionList');
        list.innerHTML = '<div class="loading">Loading...</div>';

        const { search, status, priority, sortBy, sortDir } = state.listFilters;
        const params = new URLSearchParams();
        if (search) params.set('search', search);
        if (status) params.set('status', status);
        if (priority) params.set('priority', priority);
        if (sortBy && sortBy !== 'created') params.set('sortBy', sortBy);
        if (sortDir && sortDir !== 'desc') params.set('sortDir', sortDir);
        const qs = params.toString();

        try {
            const [suggestions, settings, queueStatus] = await Promise.all([
                apiImpl('/suggestions' + (qs ? '?' + qs : '')),
                apiImpl('/settings'),
                apiImpl('/suggestions/execution-queue')
            ]);
            state.settings = settings;
            state.executionQueue = queueStatus;
            updateNewSuggestionBtn();

            if (suggestions.length === 0) {
                list.innerHTML = '<div class="card" style="text-align:center;color:var(--text-muted)">No suggestions yet. Be the first to suggest a change!</div>';
                return;
            }

            list.innerHTML = suggestions.map(s => renderSuggestionItem(s, settings, queueStatus)).join('');
        } catch (err) {
            list.innerHTML = '<div class="card" style="color:var(--danger)">Failed to load suggestions.</div>';
        }
    };
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function setupDom(extraHtml = '') {
    document.body.innerHTML = `
        <div id="suggestionList"></div>
        <input id="searchInput" value="" />
        <select id="statusFilter"><option value="">All</option></select>
        <select id="priorityFilter"><option value="">All</option></select>
        <select id="sortByFilter"><option value="created">Created</option></select>
        <select id="sortDirFilter"><option value="desc">Desc</option></select>
        <button id="newSuggestionBtn">New</button>
        <button id="myDraftsBtn">My Drafts</button>
        ${extraHtml}
    `;
}

function makeFakeApi(responseMap) {
    // Sort keys longest-first so more specific paths match before shorter ones
    const sortedEntries = Object.entries(responseMap).sort((a, b) => b[0].length - a[0].length);
    return async function fakeApi(path) {
        for (const [key, value] of sortedEntries) {
            if (path.startsWith(key)) return value;
        }
        throw new Error('Unexpected API call: ' + path);
    };
}

// ---------------------------------------------------------------------------
// updateNewSuggestionBtn()
// ---------------------------------------------------------------------------

describe('updateNewSuggestionBtn()', () => {
    beforeEach(() => {
        document.body.innerHTML = '<button id="newSuggestionBtn" style="">New</button>';
    });

    test('hides button when user has no permission and anon is disabled', () => {
        const state = makeState({ permissions: [], settings: { allowAnonymousSuggestions: false } });
        const updateNewSuggestionBtn = makeUpdateNewSuggestionBtn(state);
        updateNewSuggestionBtn();
        expect(document.getElementById('newSuggestionBtn').style.display).toBe('none');
    });

    test('shows button when user has CREATE_SUGGESTIONS permission', () => {
        const state = makeState({ permissions: ['CREATE_SUGGESTIONS'], settings: {} });
        const updateNewSuggestionBtn = makeUpdateNewSuggestionBtn(state);
        updateNewSuggestionBtn();
        expect(document.getElementById('newSuggestionBtn').style.display).toBe('');
    });

    test('shows button when anonymous suggestions are allowed', () => {
        const state = makeState({ permissions: [], settings: { allowAnonymousSuggestions: true } });
        const updateNewSuggestionBtn = makeUpdateNewSuggestionBtn(state);
        updateNewSuggestionBtn();
        expect(document.getElementById('newSuggestionBtn').style.display).toBe('');
    });

    test('does nothing when button element does not exist', () => {
        document.body.innerHTML = '';
        const state = makeState();
        const updateNewSuggestionBtn = makeUpdateNewSuggestionBtn(state);
        expect(() => updateNewSuggestionBtn()).not.toThrow();
    });
});

// ---------------------------------------------------------------------------
// restoreFiltersFromUrl()
// ---------------------------------------------------------------------------

describe('restoreFiltersFromUrl()', () => {
    beforeEach(() => {
        setupDom();
    });

    test('defaults to empty strings and created/desc when no query params', () => {
        delete window.location;
        window.location = { search: '' };
        const state = makeState();
        const restoreFiltersFromUrl = makeRestoreFiltersFromUrl(state);
        restoreFiltersFromUrl();
        expect(state.listFilters).toEqual({
            search: '',
            status: '',
            priority: '',
            sortBy: 'created',
            sortDir: 'desc',
        });
    });

    test('reads search, status, priority, sortBy, sortDir from URL', () => {
        delete window.location;
        window.location = { search: '?search=hello&status=OPEN&priority=HIGH&sortBy=votes&sortDir=asc' };
        const state = makeState();
        const restoreFiltersFromUrl = makeRestoreFiltersFromUrl(state);
        restoreFiltersFromUrl();
        expect(state.listFilters.search).toBe('hello');
        expect(state.listFilters.status).toBe('OPEN');
        expect(state.listFilters.priority).toBe('HIGH');
        expect(state.listFilters.sortBy).toBe('votes');
        expect(state.listFilters.sortDir).toBe('asc');
    });

    test('sets DOM input values to match URL params', () => {
        delete window.location;
        window.location = { search: '?search=foo&status=PLANNED' };
        // Add PLANNED as an option so jsdom can set the select value
        const statusEl = document.getElementById('statusFilter');
        const opt = document.createElement('option');
        opt.value = 'PLANNED';
        statusEl.appendChild(opt);
        const state = makeState();
        const restoreFiltersFromUrl = makeRestoreFiltersFromUrl(state);
        restoreFiltersFromUrl();
        expect(document.getElementById('searchInput').value).toBe('foo');
        expect(document.getElementById('statusFilter').value).toBe('PLANNED');
    });
});

// ---------------------------------------------------------------------------
// applyFilters()
// ---------------------------------------------------------------------------

describe('applyFilters()', () => {
    beforeEach(() => {
        setupDom();
        delete window.location;
        window.location = { search: '', pathname: '/' };
        history.replaceState = jest.fn();
    });

    test('reads DOM values into state.listFilters', () => {
        document.getElementById('searchInput').value = 'test query';
        // Add PLANNED as an option so jsdom can set the select value
        const statusEl = document.getElementById('statusFilter');
        const opt = document.createElement('option');
        opt.value = 'PLANNED';
        statusEl.appendChild(opt);
        statusEl.value = 'PLANNED';
        const state = makeState();
        const loadSuggestions = jest.fn();
        const applyFilters = makeApplyFilters(state, loadSuggestions);
        applyFilters();
        expect(state.listFilters.search).toBe('test query');
        expect(state.listFilters.status).toBe('PLANNED');
    });

    test('calls loadSuggestions after updating filters', () => {
        const state = makeState();
        const loadSuggestions = jest.fn();
        const applyFilters = makeApplyFilters(state, loadSuggestions);
        applyFilters();
        expect(loadSuggestions).toHaveBeenCalledTimes(1);
    });

    test('calls history.replaceState with query string when filters are set', () => {
        document.getElementById('searchInput').value = 'foo';
        const state = makeState();
        const loadSuggestions = jest.fn();
        const applyFilters = makeApplyFilters(state, loadSuggestions);
        applyFilters();
        expect(history.replaceState).toHaveBeenCalledWith(null, '', '?search=foo');
    });

    test('calls history.replaceState with pathname only when no filters are set', () => {
        const state = makeState();
        const loadSuggestions = jest.fn();
        const applyFilters = makeApplyFilters(state, loadSuggestions);
        applyFilters();
        expect(history.replaceState).toHaveBeenCalledWith(null, '', '/');
    });

    test('does not add sortBy to URL when it is the default "created"', () => {
        document.getElementById('sortByFilter').value = 'created';
        const state = makeState();
        const loadSuggestions = jest.fn();
        const applyFilters = makeApplyFilters(state, loadSuggestions);
        applyFilters();
        const call = history.replaceState.mock.calls[0][2];
        expect(call).not.toContain('sortBy');
    });

    test('does not add sortDir to URL when it is the default "desc"', () => {
        document.getElementById('sortDirFilter').value = 'desc';
        const state = makeState();
        const loadSuggestions = jest.fn();
        const applyFilters = makeApplyFilters(state, loadSuggestions);
        applyFilters();
        const call = history.replaceState.mock.calls[0][2];
        expect(call).not.toContain('sortDir');
    });
});

// ---------------------------------------------------------------------------
// onSearchInput()
// ---------------------------------------------------------------------------

describe('onSearchInput()', () => {
    beforeEach(() => {
        jest.useFakeTimers();
    });

    afterEach(() => {
        jest.useRealTimers();
    });

    test('debounces applyFilters by 300ms', () => {
        const state = makeState();
        const applyFilters = jest.fn();
        const onSearchInput = makeOnSearchInput(state, applyFilters);

        onSearchInput();
        expect(applyFilters).not.toHaveBeenCalled();

        jest.advanceTimersByTime(300);
        expect(applyFilters).toHaveBeenCalledTimes(1);
    });

    test('cancels previous debounce when called again before 300ms', () => {
        const state = makeState();
        const applyFilters = jest.fn();
        const onSearchInput = makeOnSearchInput(state, applyFilters);

        onSearchInput();
        jest.advanceTimersByTime(100);
        onSearchInput();
        jest.advanceTimersByTime(300);
        expect(applyFilters).toHaveBeenCalledTimes(1);
    });
});

// ---------------------------------------------------------------------------
// renderSuggestionItem()
// ---------------------------------------------------------------------------

describe('renderSuggestionItem()', () => {
    const esc = makeEsc();
    const timeAgo = makeTimeAgo();
    const emptySuggestion = {
        id: 1,
        title: 'Test suggestion',
        authorName: 'Alice',
        createdAt: new Date(Date.now() - 60000).toISOString(),
        status: 'PLANNED',
        priority: 'MEDIUM',
        upVotes: 3,
        downVotes: 1,
        currentPhase: null,
    };
    const defaultSettings = { allowVoting: false };
    const defaultQueue = { maxConcurrent: 1, activeCount: 0, queuedCount: 0, queued: [] };

    test('renders suggestion title and author', () => {
        const state = makeState();
        const render = makeRenderSuggestionItem(state, esc, timeAgo);
        const html = render(emptySuggestion, defaultSettings, defaultQueue);
        expect(html).toContain('Test suggestion');
        expect(html).toContain('Alice');
    });

    test('renders priority and status badges', () => {
        const state = makeState();
        const render = makeRenderSuggestionItem(state, esc, timeAgo);
        const html = render(emptySuggestion, defaultSettings, defaultQueue);
        expect(html).toContain('priority-MEDIUM');
        expect(html).toContain('status-PLANNED');
    });

    test('defaults priority to MEDIUM when not set', () => {
        const state = makeState();
        const render = makeRenderSuggestionItem(state, esc, timeAgo);
        const s = { ...emptySuggestion, priority: null };
        const html = render(s, defaultSettings, defaultQueue);
        expect(html).toContain('priority-MEDIUM');
    });

    test('shows vote counts when settings.allowVoting is true', () => {
        const state = makeState();
        const render = makeRenderSuggestionItem(state, esc, timeAgo);
        const html = render(emptySuggestion, { allowVoting: true }, defaultQueue);
        expect(html).toContain('3');
        expect(html).toContain('1');
    });

    test('hides vote counts when settings.allowVoting is false', () => {
        const state = makeState();
        const render = makeRenderSuggestionItem(state, esc, timeAgo);
        const html = render(emptySuggestion, { allowVoting: false }, defaultQueue);
        expect(html).not.toContain('&#9650;');
    });

    test('shows approve/deny buttons for PLANNED suggestion when user has permission', () => {
        const state = makeState({ permissions: ['APPROVE_DENY_SUGGESTIONS'] });
        const render = makeRenderSuggestionItem(state, esc, timeAgo);
        const html = render(emptySuggestion, defaultSettings, defaultQueue);
        expect(html).toContain('approveSuggestion');
        expect(html).toContain('denySuggestion');
    });

    test('does not show approve/deny buttons without permission', () => {
        const state = makeState({ permissions: [] });
        const render = makeRenderSuggestionItem(state, esc, timeAgo);
        const html = render(emptySuggestion, defaultSettings, defaultQueue);
        expect(html).not.toContain('approveSuggestion');
    });

    test('does not show approve/deny buttons for non-PLANNED/DISCUSSING status', () => {
        const state = makeState({ permissions: ['APPROVE_DENY_SUGGESTIONS'] });
        const render = makeRenderSuggestionItem(state, esc, timeAgo);
        const s = { ...emptySuggestion, status: 'APPROVED' };
        const html = render(s, defaultSettings, defaultQueue);
        expect(html).not.toContain('approveSuggestion');
    });

    test('shows queue position info for APPROVED suggestion in queue', () => {
        const state = makeState();
        const render = makeRenderSuggestionItem(state, esc, timeAgo);
        const s = { ...emptySuggestion, status: 'APPROVED' };
        const queue = { maxConcurrent: 2, activeCount: 1, queuedCount: 3, queued: [{ id: 1, position: 2 }] };
        const html = render(s, defaultSettings, queue);
        expect(html).toContain('Queue position: 2 of 3');
    });

    test('escapes HTML in title to prevent XSS', () => {
        const state = makeState();
        const render = makeRenderSuggestionItem(state, esc, timeAgo);
        const s = { ...emptySuggestion, title: '<script>alert(1)</script>' };
        const html = render(s, defaultSettings, defaultQueue);
        expect(html).not.toContain('<script>');
        expect(html).toContain('&lt;script&gt;');
    });

    test('renders Anonymous when authorName is null', () => {
        const state = makeState();
        const render = makeRenderSuggestionItem(state, esc, timeAgo);
        const s = { ...emptySuggestion, authorName: null };
        const html = render(s, defaultSettings, defaultQueue);
        expect(html).toContain('Anonymous');
    });

    test('shows spinner for IN_PROGRESS suggestions with a current phase', () => {
        const state = makeState();
        const render = makeRenderSuggestionItem(state, esc, timeAgo);
        const s = { ...emptySuggestion, status: 'IN_PROGRESS', currentPhase: 'Writing code' };
        const html = render(s, defaultSettings, defaultQueue);
        expect(html).toContain('spinner');
        expect(html).toContain('Writing code');
    });
});

// ---------------------------------------------------------------------------
// loadSuggestions()
// ---------------------------------------------------------------------------

describe('loadSuggestions()', () => {
    beforeEach(() => {
        setupDom();
        delete window.location;
        window.location = { search: '', pathname: '/' };
    });

    const defaultQueue = { maxConcurrent: 1, activeCount: 0, queuedCount: 0, queued: [] };
    const defaultSettings = { allowVoting: false };

    test('shows loading indicator while fetching', async () => {
        const state = makeState();
        let resolveApi;
        const apiImpl = jest.fn().mockReturnValue(new Promise(r => { resolveApi = r; }));
        const updateNewSuggestionBtn = jest.fn();
        const restoreFiltersFromUrl = jest.fn();
        const renderSuggestionItem = jest.fn();
        const loadSuggestions = makeLoadSuggestions(state, apiImpl, updateNewSuggestionBtn, restoreFiltersFromUrl, renderSuggestionItem);

        loadSuggestions();
        expect(document.getElementById('suggestionList').innerHTML).toContain('Loading');
    });

    test('renders empty state message when no suggestions returned', async () => {
        const state = makeState();
        const apiImpl = makeFakeApi({
            '/suggestions': [],
            '/settings': defaultSettings,
            '/suggestions/execution-queue': defaultQueue,
        });
        const updateNewSuggestionBtn = jest.fn();
        const restoreFiltersFromUrl = jest.fn();
        const renderSuggestionItem = jest.fn();
        const loadSuggestions = makeLoadSuggestions(state, apiImpl, updateNewSuggestionBtn, restoreFiltersFromUrl, renderSuggestionItem);

        await loadSuggestions();
        expect(document.getElementById('suggestionList').innerHTML).toContain('No suggestions yet');
    });

    test('renders suggestion items when suggestions are returned', async () => {
        const state = makeState();
        const suggestions = [
            { id: 1, title: 'Fix bug', authorName: 'Bob', status: 'PLANNED', priority: 'HIGH', upVotes: 0, downVotes: 0, createdAt: new Date().toISOString(), currentPhase: null }
        ];
        const apiImpl = makeFakeApi({
            '/suggestions': suggestions,
            '/settings': defaultSettings,
            '/suggestions/execution-queue': defaultQueue,
        });
        const updateNewSuggestionBtn = jest.fn();
        const restoreFiltersFromUrl = jest.fn();
        const esc = makeEsc();
        const timeAgo = makeTimeAgo();
        const renderSuggestionItem = makeRenderSuggestionItem(state, esc, timeAgo);
        const loadSuggestions = makeLoadSuggestions(state, apiImpl, updateNewSuggestionBtn, restoreFiltersFromUrl, renderSuggestionItem);

        await loadSuggestions();
        expect(document.getElementById('suggestionList').innerHTML).toContain('Fix bug');
    });

    test('calls updateNewSuggestionBtn after loading settings', async () => {
        const state = makeState();
        const apiImpl = makeFakeApi({
            '/suggestions': [],
            '/settings': defaultSettings,
            '/suggestions/execution-queue': defaultQueue,
        });
        const updateNewSuggestionBtn = jest.fn();
        const restoreFiltersFromUrl = jest.fn();
        const renderSuggestionItem = jest.fn();
        const loadSuggestions = makeLoadSuggestions(state, apiImpl, updateNewSuggestionBtn, restoreFiltersFromUrl, renderSuggestionItem);

        await loadSuggestions();
        expect(updateNewSuggestionBtn).toHaveBeenCalledTimes(1);
    });

    test('stores settings and executionQueue on state', async () => {
        const state = makeState();
        const settings = { allowVoting: true, allowAnonymousSuggestions: false };
        const queue = { maxConcurrent: 2, activeCount: 1, queuedCount: 3, queued: [] };
        const apiImpl = makeFakeApi({
            '/suggestions': [],
            '/settings': settings,
            '/suggestions/execution-queue': queue,
        });
        const loadSuggestions = makeLoadSuggestions(state, apiImpl, jest.fn(), jest.fn(), jest.fn());

        await loadSuggestions();
        expect(state.settings).toBe(settings);
        expect(state.executionQueue).toBe(queue);
    });

    test('shows error message when API call fails', async () => {
        const state = makeState();
        const apiImpl = jest.fn().mockRejectedValue(new Error('Network error'));
        const loadSuggestions = makeLoadSuggestions(state, apiImpl, jest.fn(), jest.fn(), jest.fn());

        await loadSuggestions();
        expect(document.getElementById('suggestionList').innerHTML).toContain('Failed to load suggestions');
    });

    test('builds query string from non-default listFilters', async () => {
        const state = makeState({
            listFilters: { search: 'foo', status: 'PLANNED', priority: 'HIGH', sortBy: 'votes', sortDir: 'asc' }
        });
        const capturedPaths = [];
        const apiImpl = async (path) => {
            capturedPaths.push(path);
            if (path.startsWith('/suggestions/execution-queue')) return defaultQueue;
            if (path.startsWith('/settings')) return defaultSettings;
            if (path.startsWith('/suggestions')) return [];
            throw new Error('unexpected: ' + path);
        };
        const loadSuggestions = makeLoadSuggestions(state, apiImpl, jest.fn(), jest.fn(), jest.fn());

        await loadSuggestions();
        const suggestionsCall = capturedPaths.find(p => p.startsWith('/suggestions?'));
        expect(suggestionsCall).toContain('search=foo');
        expect(suggestionsCall).toContain('status=PLANNED');
        expect(suggestionsCall).toContain('priority=HIGH');
        expect(suggestionsCall).toContain('sortBy=votes');
        expect(suggestionsCall).toContain('sortDir=asc');
    });

    test('resets myDraftsBtn text when loading suggestions', async () => {
        const state = makeState();
        const apiImpl = makeFakeApi({
            '/suggestions': [],
            '/settings': defaultSettings,
            '/suggestions/execution-queue': defaultQueue,
        });
        document.getElementById('myDraftsBtn').textContent = 'All Suggestions';
        const loadSuggestions = makeLoadSuggestions(state, apiImpl, jest.fn(), jest.fn(), jest.fn());

        await loadSuggestions();
        expect(document.getElementById('myDraftsBtn').textContent).toBe('My Drafts');
    });
});
