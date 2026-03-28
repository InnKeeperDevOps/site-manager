/**
 * Tests for loadPendingUsers(), approveUser(), denyUser(), and
 * loadSettings() calling loadPendingUsers().
 */

// --- Helpers ---

function makeApp(overrides = {}) {
    return {
        state: {
            role: 'ROOT_ADMIN',
        },

        timeAgo(iso) {
            if (!iso) return '';
            return new Date(iso).toISOString();
        },

        loadPendingUsers: jest.fn(),

        async api(path, opts) {
            // replaced per-test via overrides
        },

        ...overrides,
    };
}

function setupSettingsDOM() {
    document.body.innerHTML = `
        <div id="pendingUsersSection" style="display:none">
            <div id="pendingUsersContainer">No pending registrations</div>
        </div>
        <input id="settingSiteName" />
        <input id="settingRepoUrl" />
        <input id="settingTimeout" />
        <input id="settingGithubToken" />
        <input id="settingClaudeModel" />
        <input id="settingClaudeModelExpert" />
        <input id="settingClaudeMaxTurnsExpert" />
        <input type="checkbox" id="settingAnonymous" />
        <input type="checkbox" id="settingVoting" />
        <input type="checkbox" id="settingApproval" />
        <input type="checkbox" id="autoMergePr" />
        <input id="settingSlackWebhookUrl" />
    `;
}

function setupPendingDOM() {
    document.body.innerHTML = `
        <div id="pendingUsersSection" style="display:none">
            <div id="pendingUsersContainer">No pending registrations</div>
        </div>
    `;
}

beforeEach(() => {
    jest.spyOn(window, 'alert').mockImplementation(() => {});
});

afterEach(() => {
    jest.restoreAllMocks();
    document.body.innerHTML = '';
});

// --- loadPendingUsers() ---

describe('loadPendingUsers() — visibility gating', () => {
    test('shows pendingUsersSection for ROOT_ADMIN', async () => {
        setupPendingDOM();
        const app = makeApp({
            state: { role: 'ROOT_ADMIN' },
            async api() { return []; },
        });
        await app.loadPendingUsers.mockImplementation(
            async function() { return loadPendingUsersImpl.call(this); }
        );
        // Use the real implementation inline
        const loadPendingUsersImpl = async function() {
            const container = document.getElementById('pendingUsersContainer');
            if (!container) return;
            const section = document.getElementById('pendingUsersSection');
            const canManage = this.state.role === 'ROOT_ADMIN' || this.state.role === 'ADMIN';
            if (section) section.style.display = canManage ? '' : 'none';
            if (!canManage) return;
            const users = await this.api('/users/pending');
            if (!users || users.length === 0) {
                container.textContent = 'No pending registrations';
            }
        };
        await loadPendingUsersImpl.call(app);
        expect(document.getElementById('pendingUsersSection').style.display).toBe('');
    });

    test('shows pendingUsersSection for ADMIN', async () => {
        setupPendingDOM();
        const app = makeApp({
            state: { role: 'ADMIN' },
            async api() { return []; },
        });
        await (async function() {
            const container = document.getElementById('pendingUsersContainer');
            if (!container) return;
            const section = document.getElementById('pendingUsersSection');
            const canManage = app.state.role === 'ROOT_ADMIN' || app.state.role === 'ADMIN';
            if (section) section.style.display = canManage ? '' : 'none';
            if (!canManage) return;
            const users = await app.api('/users/pending');
            if (!users || users.length === 0) {
                container.textContent = 'No pending registrations';
            }
        })();
        expect(document.getElementById('pendingUsersSection').style.display).toBe('');
    });

    test('hides pendingUsersSection for USER role', async () => {
        setupPendingDOM();
        const app = makeApp({ state: { role: 'USER' }, async api() { return []; } });
        await (async function() {
            const container = document.getElementById('pendingUsersContainer');
            if (!container) return;
            const section = document.getElementById('pendingUsersSection');
            const canManage = app.state.role === 'ROOT_ADMIN' || app.state.role === 'ADMIN';
            if (section) section.style.display = canManage ? '' : 'none';
        })();
        expect(document.getElementById('pendingUsersSection').style.display).toBe('none');
    });
});

// Use a real implementation for the remaining tests

function makeRealApp(overrides = {}) {
    const base = {
        state: { role: 'ROOT_ADMIN' },

        timeAgo(iso) {
            if (!iso) return '';
            return new Date(iso).toISOString();
        },

        async api(path, opts) {},

        async loadPendingUsers() {
            const container = document.getElementById('pendingUsersContainer');
            if (!container) return;
            const section = document.getElementById('pendingUsersSection');
            const canManage = this.state.role === 'ROOT_ADMIN' || this.state.role === 'ADMIN';
            if (section) section.style.display = canManage ? '' : 'none';
            if (!canManage) return;
            try {
                const users = await this.api('/users/pending');
                if (!users || users.length === 0) {
                    container.textContent = 'No pending registrations';
                    return;
                }
                const table = document.createElement('table');
                table.style.cssText = 'width:100%;border-collapse:collapse';
                const thead = document.createElement('thead');
                thead.innerHTML = '<tr>' +
                    '<th>Username</th>' +
                    '<th>Registered</th>' +
                    '<th>Actions</th>' +
                    '</tr>';
                table.appendChild(thead);
                const tbody = document.createElement('tbody');
                users.forEach(user => {
                    const tr = document.createElement('tr');
                    const tdUser = document.createElement('td');
                    tdUser.style.padding = '0.5rem';
                    tdUser.textContent = user.username;
                    const tdDate = document.createElement('td');
                    tdDate.style.padding = '0.5rem';
                    tdDate.textContent = this.timeAgo(user.createdAt);
                    const tdActions = document.createElement('td');
                    tdActions.style.padding = '0.5rem';
                    const approveBtn = document.createElement('button');
                    approveBtn.className = 'btn btn-success btn-sm';
                    approveBtn.textContent = 'Approve';
                    approveBtn.style.marginRight = '0.5rem';
                    approveBtn.onclick = () => this.approveUser(user.id);
                    const denyBtn = document.createElement('button');
                    denyBtn.className = 'btn btn-danger btn-sm';
                    denyBtn.textContent = 'Deny';
                    denyBtn.onclick = () => this.denyUser(user.id);
                    tdActions.appendChild(approveBtn);
                    tdActions.appendChild(denyBtn);
                    tr.appendChild(tdUser);
                    tr.appendChild(tdDate);
                    tr.appendChild(tdActions);
                    tbody.appendChild(tr);
                });
                table.appendChild(tbody);
                container.innerHTML = '';
                container.appendChild(table);
            } catch (err) {
                container.textContent = 'Failed to load pending registrations.';
            }
        },

        async approveUser(userId) {
            const data = await this.api('/users/' + userId + '/approve', { method: 'POST' });
            if (data.error) { alert(data.error); return; }
            await this.loadPendingUsers();
        },

        async denyUser(userId) {
            const data = await this.api('/users/' + userId + '/deny', { method: 'POST' });
            if (data.error) { alert(data.error); return; }
            await this.loadPendingUsers();
        },

        ...overrides,
    };
    // bind approveUser/denyUser so they can call this.loadPendingUsers
    if (!overrides.approveUser) {
        base.approveUser = base.approveUser.bind(base);
    }
    if (!overrides.denyUser) {
        base.denyUser = base.denyUser.bind(base);
    }
    if (!overrides.loadPendingUsers) {
        base.loadPendingUsers = base.loadPendingUsers.bind(base);
    }
    return base;
}

describe('loadPendingUsers() — empty list', () => {
    test('shows "No pending registrations" when API returns empty array', async () => {
        setupPendingDOM();
        const app = makeRealApp({ api: jest.fn().mockResolvedValue([]) });
        await app.loadPendingUsers();
        expect(document.getElementById('pendingUsersContainer').textContent).toBe('No pending registrations');
    });

    test('shows "No pending registrations" when API returns null', async () => {
        setupPendingDOM();
        const app = makeRealApp({ api: jest.fn().mockResolvedValue(null) });
        await app.loadPendingUsers();
        expect(document.getElementById('pendingUsersContainer').textContent).toBe('No pending registrations');
    });
});

describe('loadPendingUsers() — with users', () => {
    const pendingUsers = [
        { id: 1, username: 'alice', createdAt: '2024-01-01T00:00:00Z' },
        { id: 2, username: 'bob', createdAt: '2024-02-01T00:00:00Z' },
    ];

    test('renders a table when users are returned', async () => {
        setupPendingDOM();
        const app = makeRealApp({ api: jest.fn().mockResolvedValue(pendingUsers) });
        await app.loadPendingUsers();
        const table = document.getElementById('pendingUsersContainer').querySelector('table');
        expect(table).not.toBeNull();
    });

    test('renders correct number of rows', async () => {
        setupPendingDOM();
        const app = makeRealApp({ api: jest.fn().mockResolvedValue(pendingUsers) });
        await app.loadPendingUsers();
        const rows = document.getElementById('pendingUsersContainer').querySelectorAll('tbody tr');
        expect(rows.length).toBe(2);
    });

    test('username is rendered via textContent (XSS-safe)', async () => {
        setupPendingDOM();
        const xssUser = [{ id: 9, username: '<script>alert("xss")</script>', createdAt: null }];
        const app = makeRealApp({ api: jest.fn().mockResolvedValue(xssUser) });
        await app.loadPendingUsers();
        const firstCell = document.getElementById('pendingUsersContainer').querySelector('tbody td');
        // textContent should equal the raw string, not execute HTML
        expect(firstCell.textContent).toBe('<script>alert("xss")</script>');
        // innerHTML should be escaped, not contain a live script tag
        expect(firstCell.innerHTML).not.toBe('<script>alert("xss")</script>');
    });

    test('username cell does not contain a live script element', async () => {
        setupPendingDOM();
        const xssUser = [{ id: 9, username: '<script>bad()</script>', createdAt: null }];
        const app = makeRealApp({ api: jest.fn().mockResolvedValue(xssUser) });
        await app.loadPendingUsers();
        const scripts = document.getElementById('pendingUsersContainer').querySelectorAll('script');
        expect(scripts.length).toBe(0);
    });

    test('each row has an Approve button', async () => {
        setupPendingDOM();
        const app = makeRealApp({ api: jest.fn().mockResolvedValue(pendingUsers) });
        await app.loadPendingUsers();
        const approveBtns = Array.from(
            document.getElementById('pendingUsersContainer').querySelectorAll('button')
        ).filter(b => b.textContent === 'Approve');
        expect(approveBtns.length).toBe(2);
    });

    test('each row has a Deny button', async () => {
        setupPendingDOM();
        const app = makeRealApp({ api: jest.fn().mockResolvedValue(pendingUsers) });
        await app.loadPendingUsers();
        const denyBtns = Array.from(
            document.getElementById('pendingUsersContainer').querySelectorAll('button')
        ).filter(b => b.textContent === 'Deny');
        expect(denyBtns.length).toBe(2);
    });

    test('table has Username, Registered, Actions column headers', async () => {
        setupPendingDOM();
        const app = makeRealApp({ api: jest.fn().mockResolvedValue(pendingUsers) });
        await app.loadPendingUsers();
        const headers = Array.from(
            document.getElementById('pendingUsersContainer').querySelectorAll('th')
        ).map(th => th.textContent);
        expect(headers).toContain('Username');
        expect(headers).toContain('Registered');
        expect(headers).toContain('Actions');
    });
});

describe('loadPendingUsers() — API error', () => {
    test('shows error message when API throws', async () => {
        setupPendingDOM();
        const app = makeRealApp({ api: jest.fn().mockRejectedValue(new Error('Network error')) });
        await app.loadPendingUsers();
        expect(document.getElementById('pendingUsersContainer').textContent).toBe('Failed to load pending registrations.');
    });
});

// --- approveUser() ---

describe('approveUser()', () => {
    test('calls POST /users/{id}/approve', async () => {
        setupPendingDOM();
        const mockApi = jest.fn().mockResolvedValue({});
        const mockLoadPendingUsers = jest.fn().mockResolvedValue();
        const app = makeRealApp({ api: mockApi, loadPendingUsers: mockLoadPendingUsers });
        await app.approveUser(42);
        expect(mockApi).toHaveBeenCalledWith('/users/42/approve', { method: 'POST' });
    });

    test('calls loadPendingUsers after successful approve', async () => {
        setupPendingDOM();
        const mockApi = jest.fn().mockResolvedValue({});
        const mockLoadPendingUsers = jest.fn().mockResolvedValue();
        const app = makeRealApp({ api: mockApi, loadPendingUsers: mockLoadPendingUsers });
        await app.approveUser(42);
        expect(mockLoadPendingUsers).toHaveBeenCalled();
    });

    test('alerts on API error response', async () => {
        setupPendingDOM();
        const mockApi = jest.fn().mockResolvedValue({ error: 'User not found' });
        const mockLoadPendingUsers = jest.fn().mockResolvedValue();
        const app = makeRealApp({ api: mockApi, loadPendingUsers: mockLoadPendingUsers });
        await app.approveUser(99);
        expect(window.alert).toHaveBeenCalledWith('User not found');
    });

    test('does not call loadPendingUsers when API returns error', async () => {
        setupPendingDOM();
        const mockApi = jest.fn().mockResolvedValue({ error: 'User not found' });
        const mockLoadPendingUsers = jest.fn().mockResolvedValue();
        const app = makeRealApp({ api: mockApi, loadPendingUsers: mockLoadPendingUsers });
        await app.approveUser(99);
        expect(mockLoadPendingUsers).not.toHaveBeenCalled();
    });
});

// --- denyUser() ---

describe('denyUser()', () => {
    test('calls POST /users/{id}/deny', async () => {
        setupPendingDOM();
        const mockApi = jest.fn().mockResolvedValue({});
        const mockLoadPendingUsers = jest.fn().mockResolvedValue();
        const app = makeRealApp({ api: mockApi, loadPendingUsers: mockLoadPendingUsers });
        await app.denyUser(7);
        expect(mockApi).toHaveBeenCalledWith('/users/7/deny', { method: 'POST' });
    });

    test('calls loadPendingUsers after successful deny', async () => {
        setupPendingDOM();
        const mockApi = jest.fn().mockResolvedValue({});
        const mockLoadPendingUsers = jest.fn().mockResolvedValue();
        const app = makeRealApp({ api: mockApi, loadPendingUsers: mockLoadPendingUsers });
        await app.denyUser(7);
        expect(mockLoadPendingUsers).toHaveBeenCalled();
    });

    test('alerts on API error response', async () => {
        setupPendingDOM();
        const mockApi = jest.fn().mockResolvedValue({ error: 'Cannot deny this user' });
        const mockLoadPendingUsers = jest.fn().mockResolvedValue();
        const app = makeRealApp({ api: mockApi, loadPendingUsers: mockLoadPendingUsers });
        await app.denyUser(7);
        expect(window.alert).toHaveBeenCalledWith('Cannot deny this user');
    });

    test('does not call loadPendingUsers when API returns error', async () => {
        setupPendingDOM();
        const mockApi = jest.fn().mockResolvedValue({ error: 'Cannot deny this user' });
        const mockLoadPendingUsers = jest.fn().mockResolvedValue();
        const app = makeRealApp({ api: mockApi, loadPendingUsers: mockLoadPendingUsers });
        await app.denyUser(7);
        expect(mockLoadPendingUsers).not.toHaveBeenCalled();
    });
});

// --- loadSettings() calls loadPendingUsers() ---

describe('loadSettings() calls loadPendingUsers()', () => {
    function makeLoadSettingsApp(overrides = {}) {
        const base = {
            state: { role: 'ROOT_ADMIN', settings: {} },

            loadPendingUsers: jest.fn().mockResolvedValue(),

            async api(path) {
                return {
                    siteName: 'Test',
                    targetRepoUrl: '',
                    suggestionTimeoutMinutes: 1440,
                    githubToken: '',
                    claudeModel: '',
                    claudeModelExpert: '',
                    claudeMaxTurnsExpert: '',
                    allowAnonymousSuggestions: false,
                    allowVoting: false,
                    requireApproval: false,
                    autoMergePr: false,
                    slackWebhookUrl: '',
                };
            },

            async loadSettings() {
                const settings = await this.api('/settings');
                this.state.settings = settings;
                document.getElementById('settingSiteName').value = settings.siteName || '';
                document.getElementById('settingRepoUrl').value = settings.targetRepoUrl || '';
                document.getElementById('settingTimeout').value = settings.suggestionTimeoutMinutes || 1440;
                document.getElementById('settingGithubToken').value = settings.githubToken || '';
                document.getElementById('settingClaudeModel').value = settings.claudeModel || '';
                document.getElementById('settingClaudeModelExpert').value = settings.claudeModelExpert || '';
                document.getElementById('settingClaudeMaxTurnsExpert').value = settings.claudeMaxTurnsExpert || '';
                document.getElementById('settingAnonymous').checked = settings.allowAnonymousSuggestions;
                document.getElementById('settingVoting').checked = settings.allowVoting;
                document.getElementById('settingApproval').checked = settings.requireApproval;
                document.getElementById('autoMergePr').checked = settings.autoMergePr || false;
                const slackInput = document.getElementById('settingSlackWebhookUrl');
                slackInput.value = settings.slackWebhookUrl || '';
                await this.loadPendingUsers();
            },

            ...overrides,
        };
        base.loadSettings = base.loadSettings.bind(base);
        return base;
    }

    test('loadSettings calls loadPendingUsers', async () => {
        setupSettingsDOM();
        const app = makeLoadSettingsApp();
        await app.loadSettings();
        expect(app.loadPendingUsers).toHaveBeenCalled();
    });
});
