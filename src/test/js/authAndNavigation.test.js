/**
 * Tests for auth.js and navigation.js modules.
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
        setupRequired: false,
        permissions: [],
        settings: {},
        notificationWs: null,
        notificationWsReconnectTimeout: null,
        myDraftsMode: false,
        currentView: null,
        ...overrides,
    };
}

// ---------------------------------------------------------------------------
// updateHeader()
// ---------------------------------------------------------------------------

function makeUpdateHeader(state) {
    function updateNewSuggestionBtn() {
        const btn = document.getElementById('newSuggestionBtn');
        if (!btn) return;
        const allowAnon = state.settings.allowAnonymousSuggestions;
        const hasPermission = state.permissions.includes('CREATE_SUGGESTIONS');
        btn.style.display = (allowAnon || hasPermission) ? '' : 'none';
    }
    function updateAiRecommendationsBtn() {
        const btn = document.getElementById('aiRecommendationsBtn');
        if (!btn) return;
        btn.style.display = (state.loggedIn && (state.role === 'ROOT_ADMIN' || state.role === 'ADMIN')) ? '' : 'none';
    }
    function updateProjectDefinitionBtn() {
        const btn = document.getElementById('project-def-btn');
        if (!btn) return;
        btn.style.display = (state.loggedIn && (state.role === 'ROOT_ADMIN' || state.role === 'ADMIN')) ? '' : 'none';
    }
    function updateMyDraftsBtn() {
        const btn = document.getElementById('myDraftsBtn');
        if (!btn) return;
        btn.style.display = state.loggedIn ? '' : 'none';
    }
    function updateSaveAsDraftBtn() {
        const btn = document.getElementById('saveAsDraftBtn');
        if (!btn) return;
        btn.style.display = state.loggedIn ? '' : 'none';
    }
    return function updateHeader() {
        const { loggedIn, username, role } = state;
        const badge = document.getElementById('userBadge');
        const loginBtn = document.getElementById('loginBtn');
        const logoutBtn = document.getElementById('logoutBtn');
        const settingsBtn = document.getElementById('settingsBtn');
        if (loggedIn) {
            badge.textContent = username + ' (' + role + ')';
            badge.style.display = '';
            loginBtn.style.display = 'none';
            logoutBtn.style.display = '';
            settingsBtn.style.display = (role === 'ROOT_ADMIN' || role === 'ADMIN') ? '' : 'none';
        } else {
            badge.style.display = 'none';
            loginBtn.style.display = '';
            logoutBtn.style.display = 'none';
            settingsBtn.style.display = 'none';
        }
        updateNewSuggestionBtn();
        updateAiRecommendationsBtn();
        updateProjectDefinitionBtn();
        updateMyDraftsBtn();
        updateSaveAsDraftBtn();
    };
}

function setupHeaderDom() {
    document.body.innerHTML = `
        <span id="userBadge"></span>
        <button id="loginBtn"></button>
        <button id="logoutBtn"></button>
        <button id="settingsBtn"></button>
    `;
}

afterEach(() => {
    document.body.innerHTML = '';
    jest.restoreAllMocks();
});

describe('updateHeader() — logged-out state', () => {
    test('hides badge and shows login button', () => {
        setupHeaderDom();
        const state = makeState({ loggedIn: false });
        makeUpdateHeader(state)();
        expect(document.getElementById('userBadge').style.display).toBe('none');
        expect(document.getElementById('loginBtn').style.display).toBe('');
    });

    test('hides logout and settings buttons', () => {
        setupHeaderDom();
        const state = makeState({ loggedIn: false });
        makeUpdateHeader(state)();
        expect(document.getElementById('logoutBtn').style.display).toBe('none');
        expect(document.getElementById('settingsBtn').style.display).toBe('none');
    });
});

describe('updateHeader() — logged-in state', () => {
    test('shows badge with username and role', () => {
        setupHeaderDom();
        const state = makeState({ loggedIn: true, username: 'alice', role: 'USER' });
        makeUpdateHeader(state)();
        expect(document.getElementById('userBadge').textContent).toBe('alice (USER)');
        expect(document.getElementById('userBadge').style.display).toBe('');
    });

    test('hides login button and shows logout button', () => {
        setupHeaderDom();
        const state = makeState({ loggedIn: true, username: 'alice', role: 'USER' });
        makeUpdateHeader(state)();
        expect(document.getElementById('loginBtn').style.display).toBe('none');
        expect(document.getElementById('logoutBtn').style.display).toBe('');
    });

    test('hides settings button for non-admin role', () => {
        setupHeaderDom();
        const state = makeState({ loggedIn: true, username: 'alice', role: 'USER' });
        makeUpdateHeader(state)();
        expect(document.getElementById('settingsBtn').style.display).toBe('none');
    });

    test('shows settings button for ADMIN role', () => {
        setupHeaderDom();
        const state = makeState({ loggedIn: true, username: 'admin', role: 'ADMIN' });
        makeUpdateHeader(state)();
        expect(document.getElementById('settingsBtn').style.display).toBe('');
    });

    test('shows settings button for ROOT_ADMIN role', () => {
        setupHeaderDom();
        const state = makeState({ loggedIn: true, username: 'root', role: 'ROOT_ADMIN' });
        makeUpdateHeader(state)();
        expect(document.getElementById('settingsBtn').style.display).toBe('');
    });
});

// ---------------------------------------------------------------------------
// updateNewSuggestionBtn()
// ---------------------------------------------------------------------------

function makeUpdateNewSuggestionBtn(state) {
    return function updateNewSuggestionBtn() {
        const btn = document.getElementById('newSuggestionBtn');
        if (!btn) return;
        const allowAnon = state.settings.allowAnonymousSuggestions;
        const hasPermission = state.permissions.includes('CREATE_SUGGESTIONS');
        btn.style.display = (allowAnon || hasPermission) ? '' : 'none';
    };
}

describe('updateNewSuggestionBtn()', () => {
    function setupBtn() {
        document.body.innerHTML = '<button id="newSuggestionBtn"></button>';
    }

    test('shows button when anonymous suggestions are allowed', () => {
        setupBtn();
        const state = makeState({ settings: { allowAnonymousSuggestions: true }, permissions: [] });
        makeUpdateNewSuggestionBtn(state)();
        expect(document.getElementById('newSuggestionBtn').style.display).toBe('');
    });

    test('shows button when user has CREATE_SUGGESTIONS permission', () => {
        setupBtn();
        const state = makeState({ settings: {}, permissions: ['CREATE_SUGGESTIONS'] });
        makeUpdateNewSuggestionBtn(state)();
        expect(document.getElementById('newSuggestionBtn').style.display).toBe('');
    });

    test('hides button when no permission and anonymous not allowed', () => {
        setupBtn();
        const state = makeState({ settings: { allowAnonymousSuggestions: false }, permissions: [] });
        makeUpdateNewSuggestionBtn(state)();
        expect(document.getElementById('newSuggestionBtn').style.display).toBe('none');
    });

    test('does nothing when button element does not exist', () => {
        document.body.innerHTML = '';
        const state = makeState({ settings: {}, permissions: [] });
        expect(() => makeUpdateNewSuggestionBtn(state)()).not.toThrow();
    });
});

// ---------------------------------------------------------------------------
// login()
// ---------------------------------------------------------------------------

function makeLogin(state, { apiImpl, navigateFn, updateHeaderFn, connectNotificationsWsFn } = {}) {
    const navigate = navigateFn || jest.fn();
    const updateHeader = updateHeaderFn || jest.fn();
    const connectNotificationsWs = connectNotificationsWsFn || jest.fn();
    const apiCall = apiImpl || jest.fn();

    return async function login(e) {
        e.preventDefault();
        const data = await apiCall('/auth/login', {
            method: 'POST',
            body: JSON.stringify({
                username: document.getElementById('loginUsername').value,
                password: document.getElementById('loginPassword').value
            })
        });
        if (data.error) { alert(data.error); return; }
        state.loggedIn = true;
        state.username = data.username;
        state.role = data.role;
        updateHeader();
        Notification.requestPermission();
        connectNotificationsWs(state.username);
        navigate('list');
    };
}

function setupLoginForm({ username = '', password = '' } = {}) {
    document.body.innerHTML = `
        <input id="loginUsername" value="${username}" />
        <input id="loginPassword" value="${password}" />
    `;
}

describe('login()', () => {
    beforeEach(() => {
        jest.spyOn(window, 'alert').mockImplementation(() => {});
        global.Notification = { requestPermission: jest.fn() };
    });

    test('calls e.preventDefault()', async () => {
        setupLoginForm({ username: 'alice', password: 'pass' });
        const state = makeState();
        const mockApi = jest.fn().mockResolvedValue({ username: 'alice', role: 'USER' });
        const login = makeLogin(state, { apiImpl: mockApi });
        const e = { preventDefault: jest.fn() };
        await login(e);
        expect(e.preventDefault).toHaveBeenCalled();
    });

    test('sets state.loggedIn to true on success', async () => {
        setupLoginForm({ username: 'alice', password: 'pass' });
        const state = makeState();
        const mockApi = jest.fn().mockResolvedValue({ username: 'alice', role: 'USER' });
        const login = makeLogin(state, { apiImpl: mockApi });
        await login({ preventDefault: jest.fn() });
        expect(state.loggedIn).toBe(true);
    });

    test('sets state.username from API response', async () => {
        setupLoginForm({ username: 'alice', password: 'pass' });
        const state = makeState();
        const mockApi = jest.fn().mockResolvedValue({ username: 'alice', role: 'USER' });
        const login = makeLogin(state, { apiImpl: mockApi });
        await login({ preventDefault: jest.fn() });
        expect(state.username).toBe('alice');
    });

    test('sets state.role from API response', async () => {
        setupLoginForm({ username: 'alice', password: 'pass' });
        const state = makeState();
        const mockApi = jest.fn().mockResolvedValue({ username: 'alice', role: 'ADMIN' });
        const login = makeLogin(state, { apiImpl: mockApi });
        await login({ preventDefault: jest.fn() });
        expect(state.role).toBe('ADMIN');
    });

    test('calls updateHeader after success', async () => {
        setupLoginForm({ username: 'alice', password: 'pass' });
        const state = makeState();
        const mockApi = jest.fn().mockResolvedValue({ username: 'alice', role: 'USER' });
        const updateHeader = jest.fn();
        const login = makeLogin(state, { apiImpl: mockApi, updateHeaderFn: updateHeader });
        await login({ preventDefault: jest.fn() });
        expect(updateHeader).toHaveBeenCalled();
    });

    test('calls connectNotificationsWs with username', async () => {
        setupLoginForm({ username: 'alice', password: 'pass' });
        const state = makeState();
        const mockApi = jest.fn().mockResolvedValue({ username: 'alice', role: 'USER' });
        const connectWs = jest.fn();
        const login = makeLogin(state, { apiImpl: mockApi, connectNotificationsWsFn: connectWs });
        await login({ preventDefault: jest.fn() });
        expect(connectWs).toHaveBeenCalledWith('alice');
    });

    test('navigates to list on success', async () => {
        setupLoginForm({ username: 'alice', password: 'pass' });
        const state = makeState();
        const mockApi = jest.fn().mockResolvedValue({ username: 'alice', role: 'USER' });
        const navigateFn = jest.fn();
        const login = makeLogin(state, { apiImpl: mockApi, navigateFn });
        await login({ preventDefault: jest.fn() });
        expect(navigateFn).toHaveBeenCalledWith('list');
    });

    test('alerts on API error and does not navigate', async () => {
        setupLoginForm({ username: 'alice', password: 'wrong' });
        const state = makeState();
        const mockApi = jest.fn().mockResolvedValue({ error: 'Invalid credentials' });
        const navigateFn = jest.fn();
        const login = makeLogin(state, { apiImpl: mockApi, navigateFn });
        await login({ preventDefault: jest.fn() });
        expect(window.alert).toHaveBeenCalledWith('Invalid credentials');
        expect(navigateFn).not.toHaveBeenCalled();
    });
});

// ---------------------------------------------------------------------------
// logout()
// ---------------------------------------------------------------------------

function makeLogout(state, { apiImpl, navigateFn, updateHeaderFn } = {}) {
    const navigate = navigateFn || jest.fn();
    const updateHeader = updateHeaderFn || jest.fn();
    const apiCall = apiImpl || jest.fn().mockResolvedValue({});

    return async function logout() {
        await apiCall('/auth/logout', { method: 'POST' });
        state.loggedIn = false;
        state.username = '';
        state.role = '';
        state.notificationWs?.close();
        clearTimeout(state.notificationWsReconnectTimeout);
        updateHeader();
        navigate('list');
    };
}

describe('logout()', () => {
    test('clears state.loggedIn', async () => {
        const state = makeState({ loggedIn: true, username: 'alice', role: 'USER' });
        const logout = makeLogout(state, { apiImpl: jest.fn().mockResolvedValue({}) });
        await logout();
        expect(state.loggedIn).toBe(false);
    });

    test('clears state.username and state.role', async () => {
        const state = makeState({ loggedIn: true, username: 'alice', role: 'USER' });
        const logout = makeLogout(state, { apiImpl: jest.fn().mockResolvedValue({}) });
        await logout();
        expect(state.username).toBe('');
        expect(state.role).toBe('');
    });

    test('calls updateHeader after clearing state', async () => {
        const state = makeState({ loggedIn: true });
        const updateHeader = jest.fn();
        const logout = makeLogout(state, { apiImpl: jest.fn().mockResolvedValue({}), updateHeaderFn: updateHeader });
        await logout();
        expect(updateHeader).toHaveBeenCalled();
    });

    test('navigates to list', async () => {
        const state = makeState({ loggedIn: true });
        const navigateFn = jest.fn();
        const logout = makeLogout(state, { apiImpl: jest.fn().mockResolvedValue({}), navigateFn });
        await logout();
        expect(navigateFn).toHaveBeenCalledWith('list');
    });

    test('closes notificationWs if open', async () => {
        const mockClose = jest.fn();
        const state = makeState({ loggedIn: true, notificationWs: { close: mockClose } });
        const logout = makeLogout(state, { apiImpl: jest.fn().mockResolvedValue({}) });
        await logout();
        expect(mockClose).toHaveBeenCalled();
    });

    test('does not throw when notificationWs is null', async () => {
        const state = makeState({ loggedIn: true, notificationWs: null });
        const logout = makeLogout(state, { apiImpl: jest.fn().mockResolvedValue({}) });
        await expect(logout()).resolves.not.toThrow();
    });
});

// ---------------------------------------------------------------------------
// setup()
// ---------------------------------------------------------------------------

function makeSetup(state, { apiImpl, navigateFn, updateHeaderFn } = {}) {
    const navigate = navigateFn || jest.fn();
    const updateHeader = updateHeaderFn || jest.fn();
    const apiCall = apiImpl || jest.fn();

    return async function setup(e) {
        e.preventDefault();
        const data = await apiCall('/auth/setup', {
            method: 'POST',
            body: JSON.stringify({
                username: document.getElementById('setupUsername').value,
                password: document.getElementById('setupPassword').value
            })
        });
        if (data.error) { alert(data.error); return; }
        state.loggedIn = true;
        state.username = data.username;
        state.role = data.role;
        state.setupRequired = false;
        updateHeader();
        navigate('list');
    };
}

function setupSetupForm({ username = '', password = '' } = {}) {
    document.body.innerHTML = `
        <input id="setupUsername" value="${username}" />
        <input id="setupPassword" value="${password}" />
    `;
}

describe('setup()', () => {
    beforeEach(() => {
        jest.spyOn(window, 'alert').mockImplementation(() => {});
    });

    test('calls e.preventDefault()', async () => {
        setupSetupForm({ username: 'admin', password: 'secret' });
        const state = makeState();
        const mockApi = jest.fn().mockResolvedValue({ username: 'admin', role: 'ROOT_ADMIN' });
        const setup = makeSetup(state, { apiImpl: mockApi });
        const e = { preventDefault: jest.fn() };
        await setup(e);
        expect(e.preventDefault).toHaveBeenCalled();
    });

    test('sets state.loggedIn, username, role on success', async () => {
        setupSetupForm({ username: 'admin', password: 'secret' });
        const state = makeState();
        const mockApi = jest.fn().mockResolvedValue({ username: 'admin', role: 'ROOT_ADMIN' });
        await makeSetup(state, { apiImpl: mockApi })({ preventDefault: jest.fn() });
        expect(state.loggedIn).toBe(true);
        expect(state.username).toBe('admin');
        expect(state.role).toBe('ROOT_ADMIN');
    });

    test('clears setupRequired on success', async () => {
        setupSetupForm({ username: 'admin', password: 'secret' });
        const state = makeState({ setupRequired: true });
        const mockApi = jest.fn().mockResolvedValue({ username: 'admin', role: 'ROOT_ADMIN' });
        await makeSetup(state, { apiImpl: mockApi })({ preventDefault: jest.fn() });
        expect(state.setupRequired).toBe(false);
    });

    test('navigates to list on success', async () => {
        setupSetupForm({ username: 'admin', password: 'secret' });
        const state = makeState();
        const mockApi = jest.fn().mockResolvedValue({ username: 'admin', role: 'ROOT_ADMIN' });
        const navigateFn = jest.fn();
        await makeSetup(state, { apiImpl: mockApi, navigateFn })({ preventDefault: jest.fn() });
        expect(navigateFn).toHaveBeenCalledWith('list');
    });

    test('alerts on error and does not navigate', async () => {
        setupSetupForm({ username: 'admin', password: 'bad' });
        const state = makeState();
        const mockApi = jest.fn().mockResolvedValue({ error: 'Setup failed' });
        const navigateFn = jest.fn();
        await makeSetup(state, { apiImpl: mockApi, navigateFn })({ preventDefault: jest.fn() });
        expect(window.alert).toHaveBeenCalledWith('Setup failed');
        expect(navigateFn).not.toHaveBeenCalled();
    });
});

// ---------------------------------------------------------------------------
// navigate() — view switching
// ---------------------------------------------------------------------------

function makeNavigate(state, callbacks = {}) {
    const loadSuggestions = callbacks.loadSuggestions || jest.fn();
    const loadDashboardView = callbacks.loadDashboardView || jest.fn();
    const loadDetail = callbacks.loadDetail || jest.fn();
    const loadSettings = callbacks.loadSettings || jest.fn();
    const disconnectWs = callbacks.disconnectWs || jest.fn();
    const updateSaveAsDraftBtn = callbacks.updateSaveAsDraftBtn || jest.fn();
    const showToastFn = callbacks.showToast || jest.fn();

    function navigate(view, data) {
        if (view === 'register' && state.settings.registrationsEnabled === false) {
            showToastFn('Registrations are currently closed.');
            navigate('login');
            return;
        }
        if (view === 'create') {
            const allowAnon = state.settings.allowAnonymousSuggestions;
            const hasPermission = state.permissions.includes('CREATE_SUGGESTIONS');
            if (!state.loggedIn && !allowAnon) {
                showToastFn('Please log in to create a suggestion');
                navigate('login');
                return;
            }
            if (state.loggedIn && !hasPermission && !allowAnon) {
                showToastFn('You do not have permission to create suggestions');
                return;
            }
        }
        document.querySelectorAll('.view').forEach(v => v.classList.remove('active'));
        const el = document.getElementById(view + 'View');
        if (el) {
            el.style.display = '';
            el.classList.add('active');
        }
        disconnectWs();
        state.currentView = view;
        switch (view) {
            case 'list':
                state.myDraftsMode = false;
                loadSuggestions();
                break;
            case 'dashboard': loadDashboardView(); break;
            case 'detail': loadDetail(data); break;
            case 'settings': loadSettings(); break;
            case 'login': {
                const regDisabled = state.settings.registrationsEnabled === false;
                const createLink = document.getElementById('createAccountLink');
                const closedMsg = document.getElementById('registrationsClosedMsg');
                if (createLink) createLink.style.display = regDisabled ? 'none' : '';
                if (closedMsg) closedMsg.style.display = regDisabled ? '' : 'none';
                break;
            }
            case 'create': {
                const nameGroup = document.getElementById('anonNameGroup');
                if (nameGroup) nameGroup.style.display = state.loggedIn ? 'none' : '';
                updateSaveAsDraftBtn();
                break;
            }
        }
    }
    return navigate;
}

function setupViews(...viewNames) {
    document.body.innerHTML = viewNames
        .map(n => `<div id="${n}View" class="view" style="display:none"></div>`)
        .join('\n');
}

describe('navigate() — view activation', () => {
    test('adds active class to the target view', () => {
        setupViews('list', 'login');
        const state = makeState();
        const navigate = makeNavigate(state);
        navigate('list');
        expect(document.getElementById('listView').classList.contains('active')).toBe(true);
    });

    test('removes active class from all other views', () => {
        document.body.innerHTML = `
            <div id="listView" class="view active"></div>
            <div id="loginView" class="view"></div>
        `;
        const state = makeState();
        const navigate = makeNavigate(state);
        navigate('login');
        expect(document.getElementById('listView').classList.contains('active')).toBe(false);
        expect(document.getElementById('loginView').classList.contains('active')).toBe(true);
    });

    test('clears inline display:none so view becomes visible', () => {
        setupViews('list');
        const state = makeState();
        const navigate = makeNavigate(state);
        navigate('list');
        expect(document.getElementById('listView').style.display).toBe('');
    });

    test('sets state.currentView', () => {
        setupViews('list', 'login');
        const state = makeState();
        const navigate = makeNavigate(state);
        navigate('login');
        expect(state.currentView).toBe('login');
    });

    test('calls disconnectWs on every navigation', () => {
        setupViews('list');
        const state = makeState();
        const disconnectWs = jest.fn();
        const navigate = makeNavigate(state, { disconnectWs });
        navigate('list');
        expect(disconnectWs).toHaveBeenCalled();
    });
});

describe('navigate() — view-specific callbacks', () => {
    test('calls loadSuggestions when navigating to list', () => {
        setupViews('list');
        const state = makeState();
        const loadSuggestions = jest.fn();
        const navigate = makeNavigate(state, { loadSuggestions });
        navigate('list');
        expect(loadSuggestions).toHaveBeenCalled();
    });

    test('resets myDraftsMode when navigating to list', () => {
        setupViews('list');
        const state = makeState({ myDraftsMode: true });
        const navigate = makeNavigate(state);
        navigate('list');
        expect(state.myDraftsMode).toBe(false);
    });

    test('calls loadDashboardView when navigating to dashboard', () => {
        setupViews('dashboard');
        const state = makeState();
        const loadDashboardView = jest.fn();
        const navigate = makeNavigate(state, { loadDashboardView });
        navigate('dashboard');
        expect(loadDashboardView).toHaveBeenCalled();
    });

    test('calls loadDetail with data when navigating to detail', () => {
        setupViews('detail');
        const state = makeState();
        const loadDetail = jest.fn();
        const navigate = makeNavigate(state, { loadDetail });
        navigate('detail', { id: 42 });
        expect(loadDetail).toHaveBeenCalledWith({ id: 42 });
    });

    test('calls loadSettings when navigating to settings', () => {
        setupViews('settings');
        const state = makeState();
        const loadSettings = jest.fn();
        const navigate = makeNavigate(state, { loadSettings });
        navigate('settings');
        expect(loadSettings).toHaveBeenCalled();
    });
});

describe('navigate() — register guard', () => {
    test('redirects to login and shows toast when registrations are disabled', () => {
        setupViews('register', 'login');
        const state = makeState({ settings: { registrationsEnabled: false } });
        const showToast = jest.fn();
        const navigate = makeNavigate(state, { showToast });
        navigate('register');
        expect(showToast).toHaveBeenCalledWith('Registrations are currently closed.');
        expect(document.getElementById('loginView').classList.contains('active')).toBe(true);
        expect(document.getElementById('registerView').classList.contains('active')).toBe(false);
    });

    test('allows navigation to register when registrations are enabled', () => {
        setupViews('register', 'login');
        const state = makeState({ settings: { registrationsEnabled: true } });
        const showToast = jest.fn();
        const navigate = makeNavigate(state, { showToast });
        navigate('register');
        expect(showToast).not.toHaveBeenCalled();
        expect(document.getElementById('registerView').classList.contains('active')).toBe(true);
    });

    test('allows navigation to register when settings.registrationsEnabled is undefined', () => {
        setupViews('register');
        const state = makeState({ settings: {} });
        const navigate = makeNavigate(state);
        navigate('register');
        expect(document.getElementById('registerView').classList.contains('active')).toBe(true);
    });
});

describe('navigate() — create guard', () => {
    test('redirects unauthenticated user without anonymous permission', () => {
        setupViews('create', 'login');
        const state = makeState({ loggedIn: false, permissions: [], settings: { allowAnonymousSuggestions: false } });
        const showToast = jest.fn();
        const navigate = makeNavigate(state, { showToast });
        navigate('create');
        expect(showToast).toHaveBeenCalledWith('Please log in to create a suggestion');
        expect(document.getElementById('loginView').classList.contains('active')).toBe(true);
    });

    test('allows unauthenticated user when anonymous suggestions enabled', () => {
        setupViews('create');
        const state = makeState({ loggedIn: false, permissions: [], settings: { allowAnonymousSuggestions: true } });
        const showToast = jest.fn();
        const navigate = makeNavigate(state, { showToast });
        navigate('create');
        expect(showToast).not.toHaveBeenCalled();
        expect(document.getElementById('createView').classList.contains('active')).toBe(true);
    });

    test('blocks logged-in user without CREATE_SUGGESTIONS permission', () => {
        setupViews('create');
        const state = makeState({ loggedIn: true, permissions: [], settings: { allowAnonymousSuggestions: false } });
        const showToast = jest.fn();
        const navigate = makeNavigate(state, { showToast });
        navigate('create');
        expect(showToast).toHaveBeenCalledWith('You do not have permission to create suggestions');
        expect(document.getElementById('createView').classList.contains('active')).toBe(false);
    });

    test('allows logged-in user with CREATE_SUGGESTIONS permission', () => {
        setupViews('create');
        const state = makeState({ loggedIn: true, permissions: ['CREATE_SUGGESTIONS'], settings: { allowAnonymousSuggestions: false } });
        const navigate = makeNavigate(state);
        navigate('create');
        expect(document.getElementById('createView').classList.contains('active')).toBe(true);
    });
});

describe('navigate() — login view extras', () => {
    test('shows createAccountLink when registrations are enabled', () => {
        document.body.innerHTML = `
            <div id="loginView" class="view"></div>
            <a id="createAccountLink" style="display:none"></a>
            <span id="registrationsClosedMsg"></span>
        `;
        const state = makeState({ settings: { registrationsEnabled: true } });
        const navigate = makeNavigate(state);
        navigate('login');
        expect(document.getElementById('createAccountLink').style.display).toBe('');
        expect(document.getElementById('registrationsClosedMsg').style.display).toBe('none');
    });

    test('hides createAccountLink when registrations are disabled', () => {
        document.body.innerHTML = `
            <div id="loginView" class="view"></div>
            <a id="createAccountLink"></a>
            <span id="registrationsClosedMsg" style="display:none"></span>
        `;
        const state = makeState({ settings: { registrationsEnabled: false } });
        const navigate = makeNavigate(state);
        navigate('login');
        expect(document.getElementById('createAccountLink').style.display).toBe('none');
        expect(document.getElementById('registrationsClosedMsg').style.display).toBe('');
    });
});

describe('navigate() — create view extras', () => {
    test('shows anonNameGroup for unauthenticated users', () => {
        document.body.innerHTML = `
            <div id="createView" class="view"></div>
            <div id="anonNameGroup"></div>
        `;
        const state = makeState({ loggedIn: false, permissions: ['CREATE_SUGGESTIONS'], settings: { allowAnonymousSuggestions: true } });
        const navigate = makeNavigate(state);
        navigate('create');
        expect(document.getElementById('anonNameGroup').style.display).toBe('');
    });

    test('hides anonNameGroup for logged-in users', () => {
        document.body.innerHTML = `
            <div id="createView" class="view"></div>
            <div id="anonNameGroup"></div>
        `;
        const state = makeState({ loggedIn: true, permissions: ['CREATE_SUGGESTIONS'], settings: {} });
        const navigate = makeNavigate(state);
        navigate('create');
        expect(document.getElementById('anonNameGroup').style.display).toBe('none');
    });

    test('calls updateSaveAsDraftBtn when navigating to create', () => {
        document.body.innerHTML = `<div id="createView" class="view"></div>`;
        const state = makeState({ loggedIn: true, permissions: ['CREATE_SUGGESTIONS'], settings: {} });
        const updateSaveAsDraftBtn = jest.fn();
        const navigate = makeNavigate(state, { updateSaveAsDraftBtn });
        navigate('create');
        expect(updateSaveAsDraftBtn).toHaveBeenCalled();
    });
});

// ---------------------------------------------------------------------------
// initProjectDefinition()
// ---------------------------------------------------------------------------

function makeInitProjectDefinition(state, { fetchImpl, showProjectDefinitionModal } = {}) {
    const _showPdModal = showProjectDefinitionModal || jest.fn();

    return async function initProjectDefinition() {
        const isAdmin = state.role === 'ROOT_ADMIN' || state.role === 'ADMIN';
        if (!isAdmin) return;
        try {
            const res = await fetchImpl('/api/project-definition/state');
            if (res.status === 204) return;
            if (!res.ok) return;
            const pdState = await res.json();
            if (!pdState || !pdState.status) return;
            const btn = document.getElementById('project-def-btn');
            if (pdState.status === 'COMPLETED' || pdState.status === 'PR_OPEN') {
                if (btn) btn.textContent = 'View Definition';
            } else if (['ACTIVE', 'GENERATING', 'SAVING'].includes(pdState.status)) {
                if (btn) btn.textContent = pdState.isEdit ? 'Update Definition' : 'Project Definition';
                _showPdModal(pdState);
            }
        } catch (e) {
            // Silently ignore
        }
    };
}

function makeFetchResponse(status, body) {
    return {
        ok: status >= 200 && status < 300,
        status,
        json: () => Promise.resolve(body)
    };
}

describe('initProjectDefinition()', () => {
    test('does nothing for non-admin users', async () => {
        const state = makeState({ role: 'USER' });
        const fetchImpl = jest.fn();
        await makeInitProjectDefinition(state, { fetchImpl })();
        expect(fetchImpl).not.toHaveBeenCalled();
    });

    test('does nothing when status is 204', async () => {
        document.body.innerHTML = '<button id="project-def-btn">Project Definition</button>';
        const state = makeState({ role: 'ADMIN' });
        const fetchImpl = jest.fn().mockResolvedValue({ status: 204, ok: false, json: jest.fn() });
        await makeInitProjectDefinition(state, { fetchImpl })();
        expect(document.getElementById('project-def-btn').textContent).toBe('Project Definition');
    });

    test('sets button text to "View Definition" when status is COMPLETED', async () => {
        document.body.innerHTML = '<button id="project-def-btn">Old Text</button>';
        const state = makeState({ role: 'ADMIN' });
        const fetchImpl = jest.fn().mockResolvedValue(makeFetchResponse(200, { status: 'COMPLETED' }));
        await makeInitProjectDefinition(state, { fetchImpl })();
        expect(document.getElementById('project-def-btn').textContent).toBe('View Definition');
    });

    test('sets button text to "View Definition" when status is PR_OPEN', async () => {
        document.body.innerHTML = '<button id="project-def-btn">Old Text</button>';
        const state = makeState({ role: 'ROOT_ADMIN' });
        const fetchImpl = jest.fn().mockResolvedValue(makeFetchResponse(200, { status: 'PR_OPEN' }));
        await makeInitProjectDefinition(state, { fetchImpl })();
        expect(document.getElementById('project-def-btn').textContent).toBe('View Definition');
    });

    test('calls showProjectDefinitionModal for ACTIVE status', async () => {
        document.body.innerHTML = '<button id="project-def-btn"></button>';
        const state = makeState({ role: 'ADMIN' });
        const pdState = { status: 'ACTIVE', isEdit: false };
        const fetchImpl = jest.fn().mockResolvedValue(makeFetchResponse(200, pdState));
        const showPdModal = jest.fn();
        await makeInitProjectDefinition(state, { fetchImpl, showProjectDefinitionModal: showPdModal })();
        expect(showPdModal).toHaveBeenCalledWith(pdState);
    });

    test('calls showProjectDefinitionModal for GENERATING status', async () => {
        document.body.innerHTML = '<button id="project-def-btn"></button>';
        const state = makeState({ role: 'ADMIN' });
        const pdState = { status: 'GENERATING', isEdit: false };
        const fetchImpl = jest.fn().mockResolvedValue(makeFetchResponse(200, pdState));
        const showPdModal = jest.fn();
        await makeInitProjectDefinition(state, { fetchImpl, showProjectDefinitionModal: showPdModal })();
        expect(showPdModal).toHaveBeenCalledWith(pdState);
    });

    test('does not throw on fetch error', async () => {
        const state = makeState({ role: 'ADMIN' });
        const fetchImpl = jest.fn().mockRejectedValue(new Error('network error'));
        await expect(makeInitProjectDefinition(state, { fetchImpl })()).resolves.not.toThrow();
    });
});
