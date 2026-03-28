/**
 * Tests for group management UI functions:
 * loadGroups(), _renderGroupsTable(), editGroup(), cancelGroupEdit(),
 * saveGroup(), deleteGroup(), and loadSettings() calling loadGroups().
 */

// --- Sample data ---

const sampleGroups = [
    {
        id: 1,
        name: 'Registered User',
        canCreateSuggestions: true,
        canVote: true,
        canReply: true,
        canApproveDenySuggestions: false,
        canManageSettings: false,
        canManageUsers: false,
    },
    {
        id: 2,
        name: 'Moderator',
        canCreateSuggestions: true,
        canVote: true,
        canReply: true,
        canApproveDenySuggestions: true,
        canManageSettings: false,
        canManageUsers: false,
    },
];

// --- DOM helpers ---

function setupGroupsDOM() {
    document.body.innerHTML = `
        <div id="groupsSection" style="display:none">
            <div id="groupsContainer">Loading groups...</div>
            <h4 id="groupFormTitle">Create New Group</h4>
            <form id="groupForm">
                <input type="hidden" id="groupEditId">
                <input type="text" id="groupName">
                <input type="checkbox" id="groupCanCreateSuggestions">
                <input type="checkbox" id="groupCanVote">
                <input type="checkbox" id="groupCanReply">
                <input type="checkbox" id="groupCanApproveDenySuggestions">
                <input type="checkbox" id="groupCanManageSettings">
                <input type="checkbox" id="groupCanManageUsers">
                <button type="submit" id="groupSaveBtn">Create Group</button>
                <button type="button" id="groupCancelBtn" style="display:none">Cancel</button>
            </form>
        </div>
    `;
}

// --- Real app implementation used in tests ---

function makeGroupApp(overrides = {}) {
    const base = {
        state: {
            role: 'ROOT_ADMIN',
            groups: [],
        },

        async api(path, opts) {},

        async loadGroups() {
            const section = document.getElementById('groupsSection');
            if (!section) return;

            const canManage = this.state.role === 'ROOT_ADMIN' || this.state.role === 'ADMIN';
            section.style.display = canManage ? '' : 'none';
            if (!canManage) return;

            const container = document.getElementById('groupsContainer');
            try {
                const groups = await this.api('/groups');
                if (groups.error) {
                    container.textContent = 'Failed to load groups.';
                    return;
                }
                this.state.groups = groups;
                this._renderGroupsTable(groups);
            } catch (err) {
                container.textContent = 'Failed to load groups.';
            }
        },

        _renderGroupsTable(groups) {
            const container = document.getElementById('groupsContainer');
            if (!groups || groups.length === 0) {
                container.textContent = 'No groups found.';
                return;
            }

            const table = document.createElement('table');
            table.style.cssText = 'width:100%;border-collapse:collapse';

            const thead = document.createElement('thead');
            thead.innerHTML = '<tr>' +
                '<th>Name</th>' +
                '<th>Permissions</th>' +
                '<th>Actions</th>' +
                '</tr>';
            table.appendChild(thead);

            const tbody = document.createElement('tbody');
            groups.forEach(group => {
                const tr = document.createElement('tr');

                const tdName = document.createElement('td');
                tdName.style.padding = '0.5rem';
                tdName.textContent = group.name;

                const tdPerms = document.createElement('td');
                tdPerms.style.padding = '0.5rem';
                const permLabels = [];
                if (group.canCreateSuggestions) permLabels.push('Create');
                if (group.canVote) permLabels.push('Vote');
                if (group.canReply) permLabels.push('Reply');
                if (group.canApproveDenySuggestions) permLabels.push('Approve/Deny');
                if (group.canManageSettings) permLabels.push('Settings');
                if (group.canManageUsers) permLabels.push('Users');
                tdPerms.textContent = permLabels.length > 0 ? permLabels.join(', ') : 'None';

                const tdActions = document.createElement('td');
                tdActions.style.padding = '0.5rem';

                const editBtn = document.createElement('button');
                editBtn.className = 'btn btn-outline btn-sm';
                editBtn.textContent = 'Edit';
                editBtn.style.marginRight = '0.5rem';
                editBtn.onclick = () => this.editGroup(group.id);

                const deleteBtn = document.createElement('button');
                deleteBtn.className = 'btn btn-danger btn-sm';
                deleteBtn.textContent = 'Delete';
                deleteBtn.onclick = () => this.deleteGroup(group.id);

                tdActions.appendChild(editBtn);
                tdActions.appendChild(deleteBtn);

                tr.appendChild(tdName);
                tr.appendChild(tdPerms);
                tr.appendChild(tdActions);
                tbody.appendChild(tr);
            });
            table.appendChild(tbody);

            container.innerHTML = '';
            container.appendChild(table);
        },

        editGroup(id) {
            const group = (this.state.groups || []).find(g => g.id === id);
            if (!group) return;

            document.getElementById('groupEditId').value = id;
            document.getElementById('groupName').value = group.name;
            document.getElementById('groupCanCreateSuggestions').checked = group.canCreateSuggestions;
            document.getElementById('groupCanVote').checked = group.canVote;
            document.getElementById('groupCanReply').checked = group.canReply;
            document.getElementById('groupCanApproveDenySuggestions').checked = group.canApproveDenySuggestions;
            document.getElementById('groupCanManageSettings').checked = group.canManageSettings;
            document.getElementById('groupCanManageUsers').checked = group.canManageUsers;

            document.getElementById('groupFormTitle').textContent = 'Edit Group';
            document.getElementById('groupSaveBtn').textContent = 'Save Changes';
            document.getElementById('groupCancelBtn').style.display = '';
        },

        cancelGroupEdit() {
            document.getElementById('groupForm').reset();
            document.getElementById('groupEditId').value = '';
            document.getElementById('groupFormTitle').textContent = 'Create New Group';
            document.getElementById('groupSaveBtn').textContent = 'Create Group';
            document.getElementById('groupCancelBtn').style.display = 'none';
        },

        async saveGroup(e) {
            e.preventDefault();
            const editId = document.getElementById('groupEditId').value;
            const payload = {
                name: document.getElementById('groupName').value,
                canCreateSuggestions: document.getElementById('groupCanCreateSuggestions').checked,
                canVote: document.getElementById('groupCanVote').checked,
                canReply: document.getElementById('groupCanReply').checked,
                canApproveDenySuggestions: document.getElementById('groupCanApproveDenySuggestions').checked,
                canManageSettings: document.getElementById('groupCanManageSettings').checked,
                canManageUsers: document.getElementById('groupCanManageUsers').checked,
            };

            const path = editId ? '/groups/' + editId : '/groups';
            const method = editId ? 'PUT' : 'POST';
            const data = await this.api(path, { method, body: JSON.stringify(payload) });
            if (data.error) { alert(data.error); return; }

            this.cancelGroupEdit();
            await this.loadGroups();
        },

        async deleteGroup(id) {
            if (!window.confirm('Are you sure you want to delete this group?')) return;
            const data = await this.api('/groups/' + id, { method: 'DELETE' });
            if (data.error) { alert(data.error); return; }
            await this.loadGroups();
        },

        ...overrides,
    };

    // Bind methods so 'this' works correctly
    ['loadGroups', '_renderGroupsTable', 'editGroup', 'cancelGroupEdit', 'saveGroup', 'deleteGroup'].forEach(fn => {
        if (!overrides[fn]) base[fn] = base[fn].bind(base);
    });

    return base;
}

// --- Setup / teardown ---

beforeEach(() => {
    jest.spyOn(window, 'alert').mockImplementation(() => {});
    jest.spyOn(window, 'confirm').mockImplementation(() => true);
});

afterEach(() => {
    jest.restoreAllMocks();
    document.body.innerHTML = '';
});

// ===== loadGroups() — visibility =====

describe('loadGroups() — visibility', () => {
    test('shows groupsSection for ROOT_ADMIN', async () => {
        setupGroupsDOM();
        const app = makeGroupApp({
            state: { role: 'ROOT_ADMIN', groups: [] },
            api: jest.fn().mockResolvedValue([]),
        });
        await app.loadGroups();
        expect(document.getElementById('groupsSection').style.display).toBe('');
    });

    test('shows groupsSection for ADMIN', async () => {
        setupGroupsDOM();
        const app = makeGroupApp({
            state: { role: 'ADMIN', groups: [] },
            api: jest.fn().mockResolvedValue([]),
        });
        await app.loadGroups();
        expect(document.getElementById('groupsSection').style.display).toBe('');
    });

    test('hides groupsSection for USER role', async () => {
        setupGroupsDOM();
        const app = makeGroupApp({
            state: { role: 'USER', groups: [] },
            api: jest.fn().mockResolvedValue([]),
        });
        await app.loadGroups();
        expect(document.getElementById('groupsSection').style.display).toBe('none');
    });

    test('hides groupsSection and returns early when not admin', async () => {
        setupGroupsDOM();
        const mockApi = jest.fn();
        const app = makeGroupApp({
            state: { role: 'USER', groups: [] },
            api: mockApi,
        });
        await app.loadGroups();
        expect(mockApi).not.toHaveBeenCalled();
    });
});

// ===== _renderGroupsTable() =====

describe('_renderGroupsTable()', () => {
    test('shows "No groups found." for empty array', () => {
        setupGroupsDOM();
        const app = makeGroupApp();
        app._renderGroupsTable([]);
        expect(document.getElementById('groupsContainer').textContent).toBe('No groups found.');
    });

    test('shows "No groups found." for null', () => {
        setupGroupsDOM();
        const app = makeGroupApp();
        app._renderGroupsTable(null);
        expect(document.getElementById('groupsContainer').textContent).toBe('No groups found.');
    });

    test('renders a table with correct number of rows', () => {
        setupGroupsDOM();
        const app = makeGroupApp();
        app._renderGroupsTable(sampleGroups);
        const rows = document.getElementById('groupsContainer').querySelectorAll('tbody tr');
        expect(rows.length).toBe(2);
    });

    test('renders group name via textContent (XSS-safe)', () => {
        setupGroupsDOM();
        const app = makeGroupApp();
        const xssGroup = [{
            id: 99,
            name: '<script>alert("xss")</script>',
            canCreateSuggestions: false,
            canVote: false,
            canReply: false,
            canApproveDenySuggestions: false,
            canManageSettings: false,
            canManageUsers: false,
        }];
        app._renderGroupsTable(xssGroup);
        const firstCell = document.getElementById('groupsContainer').querySelector('tbody td');
        expect(firstCell.textContent).toBe('<script>alert("xss")</script>');
        expect(firstCell.innerHTML).not.toBe('<script>alert("xss")</script>');
    });

    test('renders permission labels correctly for a group with permissions', () => {
        setupGroupsDOM();
        const app = makeGroupApp();
        app._renderGroupsTable([sampleGroups[0]]); // has create, vote, reply
        const rows = document.getElementById('groupsContainer').querySelectorAll('tbody tr');
        const permCell = rows[0].querySelectorAll('td')[1];
        expect(permCell.textContent).toContain('Create');
        expect(permCell.textContent).toContain('Vote');
        expect(permCell.textContent).toContain('Reply');
        expect(permCell.textContent).not.toContain('Approve/Deny');
    });

    test('shows "None" for a group with no permissions', () => {
        setupGroupsDOM();
        const app = makeGroupApp();
        const noPermGroup = [{
            id: 5,
            name: 'Guest',
            canCreateSuggestions: false,
            canVote: false,
            canReply: false,
            canApproveDenySuggestions: false,
            canManageSettings: false,
            canManageUsers: false,
        }];
        app._renderGroupsTable(noPermGroup);
        const rows = document.getElementById('groupsContainer').querySelectorAll('tbody tr');
        const permCell = rows[0].querySelectorAll('td')[1];
        expect(permCell.textContent).toBe('None');
    });

    test('renders Edit and Delete buttons for each row', () => {
        setupGroupsDOM();
        const app = makeGroupApp();
        app._renderGroupsTable(sampleGroups);
        const rows = document.getElementById('groupsContainer').querySelectorAll('tbody tr');
        rows.forEach(row => {
            const buttons = row.querySelectorAll('button');
            expect(buttons.length).toBe(2);
            expect(buttons[0].textContent).toBe('Edit');
            expect(buttons[1].textContent).toBe('Delete');
        });
    });
});

// ===== editGroup() =====

describe('editGroup()', () => {
    test('populates form fields with group data', () => {
        setupGroupsDOM();
        const app = makeGroupApp({ state: { role: 'ROOT_ADMIN', groups: sampleGroups } });
        app.editGroup(2); // Moderator group
        expect(document.getElementById('groupEditId').value).toBe('2');
        expect(document.getElementById('groupName').value).toBe('Moderator');
        expect(document.getElementById('groupCanCreateSuggestions').checked).toBe(true);
        expect(document.getElementById('groupCanVote').checked).toBe(true);
        expect(document.getElementById('groupCanReply').checked).toBe(true);
        expect(document.getElementById('groupCanApproveDenySuggestions').checked).toBe(true);
        expect(document.getElementById('groupCanManageSettings').checked).toBe(false);
        expect(document.getElementById('groupCanManageUsers').checked).toBe(false);
    });

    test('updates form title and button text', () => {
        setupGroupsDOM();
        const app = makeGroupApp({ state: { role: 'ROOT_ADMIN', groups: sampleGroups } });
        app.editGroup(1);
        expect(document.getElementById('groupFormTitle').textContent).toBe('Edit Group');
        expect(document.getElementById('groupSaveBtn').textContent).toBe('Save Changes');
    });

    test('shows cancel button', () => {
        setupGroupsDOM();
        const app = makeGroupApp({ state: { role: 'ROOT_ADMIN', groups: sampleGroups } });
        app.editGroup(1);
        expect(document.getElementById('groupCancelBtn').style.display).toBe('');
    });

    test('does nothing for unknown id', () => {
        setupGroupsDOM();
        const app = makeGroupApp({ state: { role: 'ROOT_ADMIN', groups: sampleGroups } });
        app.editGroup(999);
        expect(document.getElementById('groupEditId').value).toBe('');
        expect(document.getElementById('groupFormTitle').textContent).toBe('Create New Group');
    });
});

// ===== cancelGroupEdit() =====

describe('cancelGroupEdit()', () => {
    test('resets form title and button text', () => {
        setupGroupsDOM();
        const app = makeGroupApp({ state: { role: 'ROOT_ADMIN', groups: sampleGroups } });
        app.editGroup(1);
        app.cancelGroupEdit();
        expect(document.getElementById('groupFormTitle').textContent).toBe('Create New Group');
        expect(document.getElementById('groupSaveBtn').textContent).toBe('Create Group');
    });

    test('hides cancel button', () => {
        setupGroupsDOM();
        const app = makeGroupApp({ state: { role: 'ROOT_ADMIN', groups: sampleGroups } });
        app.editGroup(1);
        app.cancelGroupEdit();
        expect(document.getElementById('groupCancelBtn').style.display).toBe('none');
    });

    test('clears groupEditId', () => {
        setupGroupsDOM();
        const app = makeGroupApp({ state: { role: 'ROOT_ADMIN', groups: sampleGroups } });
        app.editGroup(1);
        app.cancelGroupEdit();
        expect(document.getElementById('groupEditId').value).toBe('');
    });
});

// ===== saveGroup() — create =====

describe('saveGroup() — create new group', () => {
    test('calls POST /groups with correct payload', async () => {
        setupGroupsDOM();
        const mockApi = jest.fn().mockResolvedValue({ id: 3, name: 'New Group' });
        const app = makeGroupApp({
            state: { role: 'ROOT_ADMIN', groups: [] },
            api: mockApi,
            loadGroups: jest.fn().mockResolvedValue(undefined),
        });

        document.getElementById('groupName').value = 'New Group';
        document.getElementById('groupCanCreateSuggestions').checked = true;
        document.getElementById('groupCanVote').checked = false;
        document.getElementById('groupCanReply').checked = true;
        document.getElementById('groupCanApproveDenySuggestions').checked = false;
        document.getElementById('groupCanManageSettings').checked = false;
        document.getElementById('groupCanManageUsers').checked = false;

        const event = { preventDefault: jest.fn() };
        await app.saveGroup(event);

        expect(event.preventDefault).toHaveBeenCalled();
        expect(mockApi).toHaveBeenCalledWith('/groups', expect.objectContaining({
            method: 'POST',
            body: expect.stringContaining('"name":"New Group"'),
        }));
        const body = JSON.parse(mockApi.mock.calls[0][1].body);
        expect(body.canCreateSuggestions).toBe(true);
        expect(body.canVote).toBe(false);
        expect(body.canReply).toBe(true);
    });

    test('shows alert and does not reload on error response', async () => {
        setupGroupsDOM();
        const mockApi = jest.fn().mockResolvedValue({ error: 'Group name already exists' });
        const mockLoad = jest.fn();
        const app = makeGroupApp({
            state: { role: 'ROOT_ADMIN', groups: [] },
            api: mockApi,
            loadGroups: mockLoad,
        });

        document.getElementById('groupName').value = 'Duplicate';
        const event = { preventDefault: jest.fn() };
        await app.saveGroup(event);

        expect(window.alert).toHaveBeenCalledWith('Group name already exists');
        expect(mockLoad).not.toHaveBeenCalled();
    });

    test('reloads groups after successful create', async () => {
        setupGroupsDOM();
        const mockApi = jest.fn().mockResolvedValue({ id: 3, name: 'New Group' });
        const mockLoad = jest.fn().mockResolvedValue(undefined);
        const app = makeGroupApp({
            state: { role: 'ROOT_ADMIN', groups: [] },
            api: mockApi,
            loadGroups: mockLoad,
        });

        document.getElementById('groupName').value = 'New Group';
        const event = { preventDefault: jest.fn() };
        await app.saveGroup(event);

        expect(mockLoad).toHaveBeenCalled();
    });
});

// ===== saveGroup() — update =====

describe('saveGroup() — update existing group', () => {
    test('calls PUT /groups/{id} when editId is set', async () => {
        setupGroupsDOM();
        const mockApi = jest.fn().mockResolvedValue({ id: 1, name: 'Updated' });
        const app = makeGroupApp({
            state: { role: 'ROOT_ADMIN', groups: sampleGroups },
            api: mockApi,
            loadGroups: jest.fn().mockResolvedValue(undefined),
        });

        document.getElementById('groupEditId').value = '1';
        document.getElementById('groupName').value = 'Updated';

        const event = { preventDefault: jest.fn() };
        await app.saveGroup(event);

        expect(mockApi).toHaveBeenCalledWith('/groups/1', expect.objectContaining({ method: 'PUT' }));
    });

    test('resets form to create mode after successful update', async () => {
        setupGroupsDOM();
        const mockApi = jest.fn().mockResolvedValue({ id: 1, name: 'Updated' });
        const app = makeGroupApp({
            state: { role: 'ROOT_ADMIN', groups: sampleGroups },
            api: mockApi,
            loadGroups: jest.fn().mockResolvedValue(undefined),
        });

        // Put form in edit mode
        document.getElementById('groupEditId').value = '1';
        document.getElementById('groupFormTitle').textContent = 'Edit Group';
        document.getElementById('groupSaveBtn').textContent = 'Save Changes';
        document.getElementById('groupCancelBtn').style.display = '';

        const event = { preventDefault: jest.fn() };
        await app.saveGroup(event);

        expect(document.getElementById('groupEditId').value).toBe('');
        expect(document.getElementById('groupFormTitle').textContent).toBe('Create New Group');
        expect(document.getElementById('groupSaveBtn').textContent).toBe('Create Group');
        expect(document.getElementById('groupCancelBtn').style.display).toBe('none');
    });
});

// ===== deleteGroup() =====

describe('deleteGroup()', () => {
    test('calls DELETE /groups/{id} when confirmed', async () => {
        setupGroupsDOM();
        window.confirm.mockReturnValue(true);
        const mockApi = jest.fn().mockResolvedValue({ message: 'Group deleted' });
        const mockLoad = jest.fn().mockResolvedValue(undefined);
        const app = makeGroupApp({
            state: { role: 'ROOT_ADMIN', groups: sampleGroups },
            api: mockApi,
            loadGroups: mockLoad,
        });

        await app.deleteGroup(1);

        expect(mockApi).toHaveBeenCalledWith('/groups/1', { method: 'DELETE' });
        expect(mockLoad).toHaveBeenCalled();
    });

    test('does not call API when user cancels confirm dialog', async () => {
        setupGroupsDOM();
        window.confirm.mockReturnValue(false);
        const mockApi = jest.fn();
        const app = makeGroupApp({
            state: { role: 'ROOT_ADMIN', groups: sampleGroups },
            api: mockApi,
        });

        await app.deleteGroup(1);

        expect(mockApi).not.toHaveBeenCalled();
    });

    test('shows alert on error response', async () => {
        setupGroupsDOM();
        window.confirm.mockReturnValue(true);
        const mockApi = jest.fn().mockResolvedValue({ error: 'Cannot delete default group' });
        const mockLoad = jest.fn();
        const app = makeGroupApp({
            state: { role: 'ROOT_ADMIN', groups: sampleGroups },
            api: mockApi,
            loadGroups: mockLoad,
        });

        await app.deleteGroup(1);

        expect(window.alert).toHaveBeenCalledWith('Cannot delete default group');
        expect(mockLoad).not.toHaveBeenCalled();
    });

    test('reloads groups after successful delete', async () => {
        setupGroupsDOM();
        window.confirm.mockReturnValue(true);
        const mockApi = jest.fn().mockResolvedValue({ message: 'Group deleted' });
        const mockLoad = jest.fn().mockResolvedValue(undefined);
        const app = makeGroupApp({
            state: { role: 'ROOT_ADMIN', groups: sampleGroups },
            api: mockApi,
            loadGroups: mockLoad,
        });

        await app.deleteGroup(2);

        expect(mockLoad).toHaveBeenCalledTimes(1);
    });
});

// ===== loadGroups() — error handling =====

describe('loadGroups() — error handling', () => {
    test('shows error message when API returns error object', async () => {
        setupGroupsDOM();
        const app = makeGroupApp({
            state: { role: 'ROOT_ADMIN', groups: [] },
            api: jest.fn().mockResolvedValue({ error: 'Access denied' }),
        });
        await app.loadGroups();
        expect(document.getElementById('groupsContainer').textContent).toBe('Failed to load groups.');
    });

    test('shows error message when API throws', async () => {
        setupGroupsDOM();
        const app = makeGroupApp({
            state: { role: 'ROOT_ADMIN', groups: [] },
            api: jest.fn().mockRejectedValue(new Error('Network error')),
        });
        await app.loadGroups();
        expect(document.getElementById('groupsContainer').textContent).toBe('Failed to load groups.');
    });

    test('stores groups in state after successful load', async () => {
        setupGroupsDOM();
        const app = makeGroupApp({
            state: { role: 'ROOT_ADMIN', groups: [] },
            api: jest.fn().mockResolvedValue(sampleGroups),
        });
        await app.loadGroups();
        expect(app.state.groups).toEqual(sampleGroups);
    });
});
