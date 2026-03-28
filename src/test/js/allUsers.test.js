/**
 * Tests for loadAllUsers(), _renderAllUsersTable(), assignUserGroup(), and showUserTab().
 */

// --- Sample data ---

const sampleGroups = [
    { id: 1, name: 'Registered User' },
    { id: 2, name: 'Moderator' },
];

const sampleUsers = [
    {
        id: 1,
        username: 'alice',
        role: 'ROOT_ADMIN',
        groupId: null,
        groupName: null,
        approved: true,
        denied: false,
        createdAt: '2024-01-01T00:00:00Z',
    },
    {
        id: 2,
        username: 'bob',
        role: 'USER',
        groupId: 1,
        groupName: 'Registered User',
        approved: true,
        denied: false,
        createdAt: '2024-02-01T00:00:00Z',
    },
    {
        id: 3,
        username: 'carol',
        role: 'USER',
        groupId: null,
        groupName: null,
        approved: false,
        denied: false,
        createdAt: '2024-03-01T00:00:00Z',
    },
    {
        id: 4,
        username: 'dave',
        role: 'USER',
        groupId: null,
        groupName: null,
        approved: false,
        denied: true,
        createdAt: '2024-04-01T00:00:00Z',
    },
];

// --- DOM setup ---

function setupAllUsersDOM() {
    document.body.innerHTML = `
        <div id="allUsersSection" style="display:none">
            <button id="tabPendingBtn" class="btn btn-primary btn-sm">Pending Approval</button>
            <button id="tabAllBtn" class="btn btn-outline btn-sm">All Users</button>
            <div id="pendingUsersContainer">No pending registrations</div>
            <div id="allUsersContainer" style="display:none">Loading users...</div>
        </div>
    `;
}

// --- App factory ---

function makeAllUsersApp(overrides = {}) {
    const base = {
        state: {
            role: 'ROOT_ADMIN',
            groups: sampleGroups,
        },

        async api(path, opts) {},

        showUserTab(tab) {
            const pendingContainer = document.getElementById('pendingUsersContainer');
            const allContainer = document.getElementById('allUsersContainer');
            const pendingBtn = document.getElementById('tabPendingBtn');
            const allBtn = document.getElementById('tabAllBtn');
            if (!pendingContainer || !allContainer) return;
            if (tab === 'all') {
                pendingContainer.style.display = 'none';
                allContainer.style.display = '';
                if (pendingBtn) { pendingBtn.className = 'btn btn-outline btn-sm'; }
                if (allBtn) { allBtn.className = 'btn btn-primary btn-sm'; }
            } else {
                pendingContainer.style.display = '';
                allContainer.style.display = 'none';
                if (pendingBtn) { pendingBtn.className = 'btn btn-primary btn-sm'; }
                if (allBtn) { allBtn.className = 'btn btn-outline btn-sm'; }
            }
        },

        async loadAllUsers() {
            const container = document.getElementById('allUsersContainer');
            if (!container) return;
            const canManage = this.state.role === 'ROOT_ADMIN' || this.state.role === 'ADMIN';
            if (!canManage) return;
            try {
                const users = await this.api('/users');
                if (users.error) {
                    container.textContent = 'Failed to load users.';
                    return;
                }
                this._renderAllUsersTable(users);
            } catch (err) {
                container.textContent = 'Failed to load users.';
            }
        },

        _renderAllUsersTable(users) {
            const container = document.getElementById('allUsersContainer');
            const groups = this.state.groups || [];
            if (!users || users.length === 0) {
                container.textContent = 'No users found.';
                return;
            }
            const table = document.createElement('table');
            table.style.cssText = 'width:100%;border-collapse:collapse';
            const thead = document.createElement('thead');
            thead.innerHTML = '<tr>' +
                '<th>Username</th>' +
                '<th>Role</th>' +
                '<th>Group</th>' +
                '<th>Status</th>' +
                '</tr>';
            table.appendChild(thead);
            const tbody = document.createElement('tbody');
            users.forEach(user => {
                const tr = document.createElement('tr');

                const tdUser = document.createElement('td');
                tdUser.style.padding = '0.5rem';
                tdUser.textContent = user.username;

                const tdRole = document.createElement('td');
                tdRole.style.padding = '0.5rem';
                const roleBadge = document.createElement('span');
                const roleLabel = user.role === 'ROOT_ADMIN' ? 'Root Admin'
                    : user.role === 'ADMIN' ? 'Admin'
                    : 'User';
                roleBadge.textContent = roleLabel;
                tdRole.appendChild(roleBadge);

                const tdGroup = document.createElement('td');
                tdGroup.style.padding = '0.5rem';
                const select = document.createElement('select');
                const noGroupOpt = document.createElement('option');
                noGroupOpt.value = '';
                noGroupOpt.textContent = '— No group —';
                select.appendChild(noGroupOpt);
                groups.forEach(g => {
                    const opt = document.createElement('option');
                    opt.value = g.id;
                    opt.textContent = g.name;
                    if (user.groupId && user.groupId === g.id) opt.selected = true;
                    select.appendChild(opt);
                });
                select.onchange = () => {
                    const gid = select.value ? parseInt(select.value, 10) : null;
                    this.assignUserGroup(user.id, gid);
                };
                tdGroup.appendChild(select);

                const tdStatus = document.createElement('td');
                tdStatus.style.padding = '0.5rem';
                const statusText = user.denied ? 'Denied'
                    : user.approved ? 'Active'
                    : 'Pending';
                tdStatus.textContent = statusText;

                tr.appendChild(tdUser);
                tr.appendChild(tdRole);
                tr.appendChild(tdGroup);
                tr.appendChild(tdStatus);
                tbody.appendChild(tr);
            });
            table.appendChild(tbody);
            container.innerHTML = '';
            container.appendChild(table);
        },

        async assignUserGroup(userId, groupId) {
            const data = await this.api('/users/' + userId + '/group', {
                method: 'PUT',
                body: JSON.stringify({ groupId: groupId })
            });
            if (data.error) { alert(data.error); }
        },

        ...overrides,
    };
    base.loadAllUsers = base.loadAllUsers.bind(base);
    base._renderAllUsersTable = base._renderAllUsersTable.bind(base);
    base.assignUserGroup = base.assignUserGroup.bind(base);
    base.showUserTab = base.showUserTab.bind(base);
    return base;
}

beforeEach(() => {
    jest.spyOn(window, 'alert').mockImplementation(() => {});
});

afterEach(() => {
    jest.restoreAllMocks();
    document.body.innerHTML = '';
});

// --- showUserTab() ---

describe('showUserTab()', () => {
    test('switches to all tab: shows allUsersContainer, hides pendingUsersContainer', () => {
        setupAllUsersDOM();
        const app = makeAllUsersApp();
        app.showUserTab('all');
        expect(document.getElementById('allUsersContainer').style.display).toBe('');
        expect(document.getElementById('pendingUsersContainer').style.display).toBe('none');
    });

    test('switches to pending tab: shows pendingUsersContainer, hides allUsersContainer', () => {
        setupAllUsersDOM();
        const app = makeAllUsersApp();
        app.showUserTab('all');
        app.showUserTab('pending');
        expect(document.getElementById('pendingUsersContainer').style.display).toBe('');
        expect(document.getElementById('allUsersContainer').style.display).toBe('none');
    });

    test('all tab sets tabAllBtn to primary style', () => {
        setupAllUsersDOM();
        const app = makeAllUsersApp();
        app.showUserTab('all');
        expect(document.getElementById('tabAllBtn').className).toContain('btn-primary');
        expect(document.getElementById('tabPendingBtn').className).toContain('btn-outline');
    });

    test('pending tab sets tabPendingBtn to primary style', () => {
        setupAllUsersDOM();
        const app = makeAllUsersApp();
        app.showUserTab('all');
        app.showUserTab('pending');
        expect(document.getElementById('tabPendingBtn').className).toContain('btn-primary');
        expect(document.getElementById('tabAllBtn').className).toContain('btn-outline');
    });

    test('does nothing if containers are missing', () => {
        document.body.innerHTML = '';
        const app = makeAllUsersApp();
        expect(() => app.showUserTab('all')).not.toThrow();
    });
});

// --- loadAllUsers() — visibility gating ---

describe('loadAllUsers() — visibility gating', () => {
    test('does not call API for USER role', async () => {
        setupAllUsersDOM();
        const mockApi = jest.fn().mockResolvedValue([]);
        const app = makeAllUsersApp({
            state: { role: 'USER', groups: sampleGroups },
            api: mockApi,
        });
        await app.loadAllUsers();
        expect(mockApi).not.toHaveBeenCalled();
    });

    test('calls API for ROOT_ADMIN', async () => {
        setupAllUsersDOM();
        const mockApi = jest.fn().mockResolvedValue([]);
        const app = makeAllUsersApp({
            state: { role: 'ROOT_ADMIN', groups: sampleGroups },
            api: mockApi,
        });
        await app.loadAllUsers();
        expect(mockApi).toHaveBeenCalledWith('/users');
    });

    test('calls API for ADMIN', async () => {
        setupAllUsersDOM();
        const mockApi = jest.fn().mockResolvedValue([]);
        const app = makeAllUsersApp({
            state: { role: 'ADMIN', groups: sampleGroups },
            api: mockApi,
        });
        await app.loadAllUsers();
        expect(mockApi).toHaveBeenCalledWith('/users');
    });
});

// --- loadAllUsers() — rendering ---

describe('loadAllUsers() — rendering', () => {
    test('shows "No users found." for empty list', async () => {
        setupAllUsersDOM();
        const app = makeAllUsersApp({ api: jest.fn().mockResolvedValue([]) });
        await app.loadAllUsers();
        expect(document.getElementById('allUsersContainer').textContent).toBe('No users found.');
    });

    test('renders a table when users are returned', async () => {
        setupAllUsersDOM();
        const app = makeAllUsersApp({ api: jest.fn().mockResolvedValue(sampleUsers) });
        await app.loadAllUsers();
        const table = document.getElementById('allUsersContainer').querySelector('table');
        expect(table).not.toBeNull();
    });

    test('renders correct number of rows', async () => {
        setupAllUsersDOM();
        const app = makeAllUsersApp({ api: jest.fn().mockResolvedValue(sampleUsers) });
        await app.loadAllUsers();
        const rows = document.getElementById('allUsersContainer').querySelectorAll('tbody tr');
        expect(rows.length).toBe(sampleUsers.length);
    });

    test('shows error message when API returns error object', async () => {
        setupAllUsersDOM();
        const app = makeAllUsersApp({ api: jest.fn().mockResolvedValue({ error: 'Access denied' }) });
        await app.loadAllUsers();
        expect(document.getElementById('allUsersContainer').textContent).toBe('Failed to load users.');
    });

    test('shows error message when API throws', async () => {
        setupAllUsersDOM();
        const app = makeAllUsersApp({ api: jest.fn().mockRejectedValue(new Error('Network error')) });
        await app.loadAllUsers();
        expect(document.getElementById('allUsersContainer').textContent).toBe('Failed to load users.');
    });
});

// --- _renderAllUsersTable() — columns ---

describe('_renderAllUsersTable() — column headers', () => {
    test('renders Username, Role, Group, Status headers', () => {
        setupAllUsersDOM();
        const app = makeAllUsersApp();
        app._renderAllUsersTable(sampleUsers);
        const headers = Array.from(
            document.getElementById('allUsersContainer').querySelectorAll('th')
        ).map(th => th.textContent);
        expect(headers).toContain('Username');
        expect(headers).toContain('Role');
        expect(headers).toContain('Group');
        expect(headers).toContain('Status');
    });
});

describe('_renderAllUsersTable() — username XSS safety', () => {
    test('username rendered via textContent (not innerHTML)', () => {
        setupAllUsersDOM();
        const app = makeAllUsersApp();
        const xssUsers = [{ id: 9, username: '<script>alert("xss")</script>', role: 'USER', groupId: null, approved: true, denied: false }];
        app._renderAllUsersTable(xssUsers);
        const firstCell = document.getElementById('allUsersContainer').querySelector('tbody td');
        expect(firstCell.textContent).toBe('<script>alert("xss")</script>');
        expect(firstCell.innerHTML).not.toBe('<script>alert("xss")</script>');
    });

    test('username cell does not contain a live script element', () => {
        setupAllUsersDOM();
        const app = makeAllUsersApp();
        const xssUsers = [{ id: 9, username: '<script>bad()</script>', role: 'USER', groupId: null, approved: true, denied: false }];
        app._renderAllUsersTable(xssUsers);
        const scripts = document.getElementById('allUsersContainer').querySelectorAll('script');
        expect(scripts.length).toBe(0);
    });
});

describe('_renderAllUsersTable() — role badges', () => {
    test('ROOT_ADMIN shows "Root Admin" badge', () => {
        setupAllUsersDOM();
        const app = makeAllUsersApp();
        app._renderAllUsersTable([sampleUsers[0]]);
        const rows = document.getElementById('allUsersContainer').querySelectorAll('tbody tr');
        expect(rows[0].cells[1].textContent).toBe('Root Admin');
    });

    test('ADMIN shows "Admin" badge', () => {
        setupAllUsersDOM();
        const app = makeAllUsersApp();
        const adminUser = { id: 10, username: 'admin1', role: 'ADMIN', groupId: null, approved: true, denied: false };
        app._renderAllUsersTable([adminUser]);
        const rows = document.getElementById('allUsersContainer').querySelectorAll('tbody tr');
        expect(rows[0].cells[1].textContent).toBe('Admin');
    });

    test('USER shows "User" badge', () => {
        setupAllUsersDOM();
        const app = makeAllUsersApp();
        app._renderAllUsersTable([sampleUsers[1]]);
        const rows = document.getElementById('allUsersContainer').querySelectorAll('tbody tr');
        expect(rows[0].cells[1].textContent).toBe('User');
    });
});

describe('_renderAllUsersTable() — group dropdown', () => {
    test('renders a select element in the Group column for each row', () => {
        setupAllUsersDOM();
        const app = makeAllUsersApp();
        app._renderAllUsersTable(sampleUsers);
        const selects = document.getElementById('allUsersContainer').querySelectorAll('select');
        expect(selects.length).toBe(sampleUsers.length);
    });

    test('group dropdown contains all groups as options', () => {
        setupAllUsersDOM();
        const app = makeAllUsersApp();
        app._renderAllUsersTable([sampleUsers[0]]);
        const select = document.getElementById('allUsersContainer').querySelector('select');
        const optionTexts = Array.from(select.options).map(o => o.textContent);
        expect(optionTexts).toContain('Registered User');
        expect(optionTexts).toContain('Moderator');
    });

    test('pre-selects the user\'s current group in the dropdown', () => {
        setupAllUsersDOM();
        const app = makeAllUsersApp();
        // bob has groupId: 1 (Registered User)
        app._renderAllUsersTable([sampleUsers[1]]);
        const select = document.getElementById('allUsersContainer').querySelector('select');
        expect(select.value).toBe('1');
    });

    test('shows "— No group —" option as first option', () => {
        setupAllUsersDOM();
        const app = makeAllUsersApp();
        app._renderAllUsersTable([sampleUsers[0]]);
        const select = document.getElementById('allUsersContainer').querySelector('select');
        expect(select.options[0].textContent).toBe('— No group —');
        expect(select.options[0].value).toBe('');
    });

    test('user with no group has empty string selected', () => {
        setupAllUsersDOM();
        const app = makeAllUsersApp();
        app._renderAllUsersTable([sampleUsers[0]]); // alice has groupId: null
        const select = document.getElementById('allUsersContainer').querySelector('select');
        expect(select.value).toBe('');
    });
});

describe('_renderAllUsersTable() — status column', () => {
    test('shows "Active" for approved users', () => {
        setupAllUsersDOM();
        const app = makeAllUsersApp();
        app._renderAllUsersTable([sampleUsers[1]]); // bob: approved=true, denied=false
        const row = document.getElementById('allUsersContainer').querySelector('tbody tr');
        expect(row.cells[3].textContent).toBe('Active');
    });

    test('shows "Pending" for unapproved, non-denied users', () => {
        setupAllUsersDOM();
        const app = makeAllUsersApp();
        app._renderAllUsersTable([sampleUsers[2]]); // carol: approved=false, denied=false
        const row = document.getElementById('allUsersContainer').querySelector('tbody tr');
        expect(row.cells[3].textContent).toBe('Pending');
    });

    test('shows "Denied" for denied users', () => {
        setupAllUsersDOM();
        const app = makeAllUsersApp();
        app._renderAllUsersTable([sampleUsers[3]]); // dave: denied=true
        const row = document.getElementById('allUsersContainer').querySelector('tbody tr');
        expect(row.cells[3].textContent).toBe('Denied');
    });
});

// --- assignUserGroup() ---

describe('assignUserGroup()', () => {
    test('calls PUT /users/{id}/group with groupId in body', async () => {
        setupAllUsersDOM();
        const mockApi = jest.fn().mockResolvedValue({});
        const app = makeAllUsersApp({ api: mockApi });
        await app.assignUserGroup(2, 1);
        expect(mockApi).toHaveBeenCalledWith('/users/2/group', {
            method: 'PUT',
            body: JSON.stringify({ groupId: 1 }),
        });
    });

    test('calls API with null groupId when removing group', async () => {
        setupAllUsersDOM();
        const mockApi = jest.fn().mockResolvedValue({});
        const app = makeAllUsersApp({ api: mockApi });
        await app.assignUserGroup(2, null);
        expect(mockApi).toHaveBeenCalledWith('/users/2/group', {
            method: 'PUT',
            body: JSON.stringify({ groupId: null }),
        });
    });

    test('alerts on API error', async () => {
        setupAllUsersDOM();
        const mockApi = jest.fn().mockResolvedValue({ error: 'User not found' });
        const app = makeAllUsersApp({ api: mockApi });
        await app.assignUserGroup(99, 1);
        expect(window.alert).toHaveBeenCalledWith('User not found');
    });

    test('does not alert on success', async () => {
        setupAllUsersDOM();
        const mockApi = jest.fn().mockResolvedValue({ id: 2, username: 'bob' });
        const app = makeAllUsersApp({ api: mockApi });
        await app.assignUserGroup(2, 1);
        expect(window.alert).not.toHaveBeenCalled();
    });
});

// --- dropdown onChange wires to assignUserGroup() ---

describe('group dropdown onChange', () => {
    test('changing dropdown calls assignUserGroup with user id and new group id', async () => {
        setupAllUsersDOM();
        const mockAssign = jest.fn().mockResolvedValue();
        const app = makeAllUsersApp({ api: jest.fn().mockResolvedValue([]) });
        app.assignUserGroup = mockAssign;
        app._renderAllUsersTable([sampleUsers[1]]); // bob: id=2, groupId=1
        const select = document.getElementById('allUsersContainer').querySelector('select');
        // Change to group id 2
        select.value = '2';
        select.onchange();
        expect(mockAssign).toHaveBeenCalledWith(2, 2);
    });

    test('selecting "— No group —" calls assignUserGroup with null', async () => {
        setupAllUsersDOM();
        const mockAssign = jest.fn().mockResolvedValue();
        const app = makeAllUsersApp({ api: jest.fn().mockResolvedValue([]) });
        app.assignUserGroup = mockAssign;
        app._renderAllUsersTable([sampleUsers[1]]); // bob: id=2, groupId=1
        const select = document.getElementById('allUsersContainer').querySelector('select');
        select.value = '';
        select.onchange();
        expect(mockAssign).toHaveBeenCalledWith(2, null);
    });
});
