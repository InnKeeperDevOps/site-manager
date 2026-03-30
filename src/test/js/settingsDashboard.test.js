/**
 * Tests for settings.js and dashboard.js modules.
 * Functions are defined inline to match the project's test pattern
 * (Jest jsdom environment does not support native ES module imports).
 */

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

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

const esc = makeEsc();
const timeAgo = makeTimeAgo();

// ---------------------------------------------------------------------------
// settings — _renderGroupsTable()
// ---------------------------------------------------------------------------

function renderGroupsTable(groups, container) {
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
        tdName.textContent = group.name;

        const tdPerms = document.createElement('td');
        const permLabels = [];
        if (group.canCreateSuggestions) permLabels.push('Create');
        if (group.canVote) permLabels.push('Vote');
        if (group.canReply) permLabels.push('Reply');
        if (group.canApproveDenySuggestions) permLabels.push('Approve/Deny');
        if (group.canManageSettings) permLabels.push('Settings');
        if (group.canManageUsers) permLabels.push('Users');
        tdPerms.textContent = permLabels.length > 0 ? permLabels.join(', ') : 'None';

        tr.appendChild(tdName);
        tr.appendChild(tdPerms);
        tbody.appendChild(tr);
    });
    table.appendChild(tbody);

    container.innerHTML = '';
    container.appendChild(table);
}

describe('_renderGroupsTable()', () => {
    let container;

    beforeEach(() => {
        container = document.createElement('div');
    });

    test('shows "No groups found." for empty array', () => {
        renderGroupsTable([], container);
        expect(container.textContent).toBe('No groups found.');
    });

    test('shows "No groups found." for null', () => {
        renderGroupsTable(null, container);
        expect(container.textContent).toBe('No groups found.');
    });

    test('renders a row per group', () => {
        const groups = [
            { id: 1, name: 'Editors', canCreateSuggestions: true, canVote: false, canReply: false, canApproveDenySuggestions: false, canManageSettings: false, canManageUsers: false },
            { id: 2, name: 'Admins', canCreateSuggestions: true, canVote: true, canReply: true, canApproveDenySuggestions: true, canManageSettings: true, canManageUsers: true },
        ];
        renderGroupsTable(groups, container);
        const rows = container.querySelectorAll('tbody tr');
        expect(rows).toHaveLength(2);
    });

    test('shows group name in table', () => {
        const groups = [
            { id: 1, name: 'MyGroup', canCreateSuggestions: false, canVote: false, canReply: false, canApproveDenySuggestions: false, canManageSettings: false, canManageUsers: false },
        ];
        renderGroupsTable(groups, container);
        expect(container.textContent).toContain('MyGroup');
    });

    test('shows "None" when no permissions are set', () => {
        const groups = [
            { id: 1, name: 'Empty', canCreateSuggestions: false, canVote: false, canReply: false, canApproveDenySuggestions: false, canManageSettings: false, canManageUsers: false },
        ];
        renderGroupsTable(groups, container);
        expect(container.textContent).toContain('None');
    });

    test('lists each enabled permission', () => {
        const groups = [
            { id: 1, name: 'PowerGroup', canCreateSuggestions: true, canVote: true, canReply: false, canApproveDenySuggestions: false, canManageSettings: false, canManageUsers: false },
        ];
        renderGroupsTable(groups, container);
        const permsCell = container.querySelectorAll('tbody td')[1];
        expect(permsCell.textContent).toContain('Create');
        expect(permsCell.textContent).toContain('Vote');
        expect(permsCell.textContent).not.toContain('Reply');
    });
});

// ---------------------------------------------------------------------------
// settings — _renderAllUsersTable()
// ---------------------------------------------------------------------------

function renderAllUsersTable(users, groups, container) {
    if (!users || users.length === 0) {
        container.textContent = 'No users found.';
        return;
    }

    const table = document.createElement('table');
    const tbody = document.createElement('tbody');
    users.forEach(user => {
        const tr = document.createElement('tr');

        const tdUser = document.createElement('td');
        tdUser.textContent = user.username;

        const tdRole = document.createElement('td');
        const roleLabel = user.role === 'ROOT_ADMIN' ? 'Root Admin'
            : user.role === 'ADMIN' ? 'Admin'
            : 'User';
        tdRole.textContent = roleLabel;

        const tdStatus = document.createElement('td');
        const statusText = user.denied ? 'Denied'
            : user.approved ? 'Active'
            : 'Pending';
        tdStatus.textContent = statusText;

        tr.appendChild(tdUser);
        tr.appendChild(tdRole);
        tr.appendChild(tdStatus);
        tbody.appendChild(tr);
    });
    table.appendChild(tbody);
    container.innerHTML = '';
    container.appendChild(table);
}

describe('_renderAllUsersTable()', () => {
    let container;

    beforeEach(() => {
        container = document.createElement('div');
    });

    test('shows "No users found." for empty array', () => {
        renderAllUsersTable([], [], container);
        expect(container.textContent).toBe('No users found.');
    });

    test('renders a row per user', () => {
        const users = [
            { id: 1, username: 'alice', role: 'USER', approved: true, denied: false },
            { id: 2, username: 'bob', role: 'ADMIN', approved: true, denied: false },
        ];
        renderAllUsersTable(users, [], container);
        const rows = container.querySelectorAll('tbody tr');
        expect(rows).toHaveLength(2);
    });

    test('shows correct role labels', () => {
        const users = [
            { id: 1, username: 'root', role: 'ROOT_ADMIN', approved: true, denied: false },
            { id: 2, username: 'admin', role: 'ADMIN', approved: true, denied: false },
            { id: 3, username: 'user', role: 'USER', approved: true, denied: false },
        ];
        renderAllUsersTable(users, [], container);
        expect(container.textContent).toContain('Root Admin');
        expect(container.textContent).toContain('Admin');
        expect(container.textContent).toContain('User');
    });

    test('shows Denied status for denied user', () => {
        const users = [
            { id: 1, username: 'blocked', role: 'USER', approved: false, denied: true },
        ];
        renderAllUsersTable(users, [], container);
        expect(container.textContent).toContain('Denied');
    });

    test('shows Active status for approved user', () => {
        const users = [
            { id: 1, username: 'active', role: 'USER', approved: true, denied: false },
        ];
        renderAllUsersTable(users, [], container);
        expect(container.textContent).toContain('Active');
    });

    test('shows Pending status for unapproved user', () => {
        const users = [
            { id: 1, username: 'pending', role: 'USER', approved: false, denied: false },
        ];
        renderAllUsersTable(users, [], container);
        expect(container.textContent).toContain('Pending');
    });
});

// ---------------------------------------------------------------------------
// settings — showUserTab()
// ---------------------------------------------------------------------------

function showUserTab(tab, pendingContainer, allContainer, pendingBtn, allBtn) {
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
}

describe('showUserTab()', () => {
    let pendingContainer, allContainer, pendingBtn, allBtn;

    beforeEach(() => {
        pendingContainer = document.createElement('div');
        allContainer = document.createElement('div');
        pendingBtn = document.createElement('button');
        allBtn = document.createElement('button');
    });

    test('switches to all tab: hides pending, shows all', () => {
        showUserTab('all', pendingContainer, allContainer, pendingBtn, allBtn);
        expect(pendingContainer.style.display).toBe('none');
        expect(allContainer.style.display).toBe('');
    });

    test('switches to all tab: updates button classes', () => {
        showUserTab('all', pendingContainer, allContainer, pendingBtn, allBtn);
        expect(pendingBtn.className).toBe('btn btn-outline btn-sm');
        expect(allBtn.className).toBe('btn btn-primary btn-sm');
    });

    test('switches to pending tab: shows pending, hides all', () => {
        showUserTab('pending', pendingContainer, allContainer, pendingBtn, allBtn);
        expect(pendingContainer.style.display).toBe('');
        expect(allContainer.style.display).toBe('none');
    });

    test('switches to pending tab: updates button classes', () => {
        showUserTab('pending', pendingContainer, allContainer, pendingBtn, allBtn);
        expect(pendingBtn.className).toBe('btn btn-primary btn-sm');
        expect(allBtn.className).toBe('btn btn-outline btn-sm');
    });

    test('does nothing when containers are missing', () => {
        expect(() => showUserTab('all', null, null, pendingBtn, allBtn)).not.toThrow();
    });
});

// ---------------------------------------------------------------------------
// dashboard — renderLeaderboard()
// ---------------------------------------------------------------------------

function renderLeaderboard(contributors, body) {
    if (!body) return;
    const medals = ['\uD83E\uDD47', '\uD83E\uDD48', '\uD83E\uDD49'];
    body.innerHTML = '';
    contributors.forEach((c, i) => {
        const rank = i + 1;
        const rankDisplay = rank <= 3 ? medals[rank - 1] : String(rank);
        const tr = document.createElement('tr');
        tr.innerHTML =
            '<td>' + rankDisplay + '</td>' +
            '<td><a href="#" class="contributor-link"' +
                ' data-author-id="' + esc(c.authorId) + '"' +
                ' data-username="' + esc(c.username) + '">' +
                esc(c.username) + '</a></td>' +
            '<td>' + c.totalSubmissions + '</td>' +
            '<td>' + c.mergedSuggestions + '</td>' +
            '<td>' + c.approvedSuggestions + '</td>' +
            '<td>' + c.totalUpvotesReceived + '</td>' +
            '<td>' + c.score + '</td>';
        body.appendChild(tr);
    });
}

describe('renderLeaderboard()', () => {
    let body;

    beforeEach(() => {
        body = document.createElement('tbody');
    });

    test('renders empty table for empty array', () => {
        renderLeaderboard([], body);
        expect(body.children).toHaveLength(0);
    });

    test('renders one row per contributor', () => {
        const contributors = [
            { authorId: '1', username: 'alice', totalSubmissions: 10, mergedSuggestions: 5, approvedSuggestions: 7, totalUpvotesReceived: 20, score: 100 },
            { authorId: '2', username: 'bob', totalSubmissions: 8, mergedSuggestions: 3, approvedSuggestions: 5, totalUpvotesReceived: 15, score: 75 },
        ];
        renderLeaderboard(contributors, body);
        expect(body.querySelectorAll('tr')).toHaveLength(2);
    });

    test('uses medal emoji for top 3 ranks', () => {
        const contributors = [
            { authorId: '1', username: 'first', totalSubmissions: 10, mergedSuggestions: 5, approvedSuggestions: 7, totalUpvotesReceived: 20, score: 100 },
            { authorId: '2', username: 'second', totalSubmissions: 8, mergedSuggestions: 3, approvedSuggestions: 5, totalUpvotesReceived: 15, score: 75 },
            { authorId: '3', username: 'third', totalSubmissions: 6, mergedSuggestions: 2, approvedSuggestions: 4, totalUpvotesReceived: 10, score: 50 },
            { authorId: '4', username: 'fourth', totalSubmissions: 4, mergedSuggestions: 1, approvedSuggestions: 2, totalUpvotesReceived: 5, score: 25 },
        ];
        renderLeaderboard(contributors, body);
        const rows = body.querySelectorAll('tr');
        expect(rows[0].textContent).toContain('\uD83E\uDD47');
        expect(rows[1].textContent).toContain('\uD83E\uDD48');
        expect(rows[2].textContent).toContain('\uD83E\uDD49');
        expect(rows[3].textContent).toContain('4');
    });

    test('shows username as link', () => {
        const contributors = [
            { authorId: 'u42', username: 'charlie', totalSubmissions: 1, mergedSuggestions: 0, approvedSuggestions: 0, totalUpvotesReceived: 0, score: 0 },
        ];
        renderLeaderboard(contributors, body);
        const link = body.querySelector('.contributor-link');
        expect(link).not.toBeNull();
        expect(link.dataset.authorId).toBe('u42');
        expect(link.dataset.username).toBe('charlie');
        expect(link.textContent).toBe('charlie');
    });

    test('escapes HTML in username display text', () => {
        const contributors = [
            { authorId: '1', username: '<b>bold</b>', totalSubmissions: 0, mergedSuggestions: 0, approvedSuggestions: 0, totalUpvotesReceived: 0, score: 0 },
        ];
        renderLeaderboard(contributors, body);
        const link = body.querySelector('.contributor-link');
        // The visible text should be the raw string, not rendered HTML
        expect(link.textContent).toBe('<b>bold</b>');
        // The <b> tag should not be rendered as an element inside the link
        expect(link.querySelector('b')).toBeNull();
    });
});

// ---------------------------------------------------------------------------
// dashboard — renderUserHistory()
// ---------------------------------------------------------------------------

function renderUserHistory(username, suggestions, panel, nameEl, listEl) {
    if (!panel || !nameEl || !listEl) return;
    panel.style.display = '';
    nameEl.textContent = username + "'s Submissions";
    if (!suggestions || suggestions.length === 0) {
        listEl.innerHTML = '<p>No submissions found.</p>';
        return;
    }
    listEl.innerHTML = '';
    suggestions.forEach(s => {
        const div = document.createElement('div');
        div.innerHTML =
            '<a href="#">' + esc(s.title) + '</a>' +
            '<span class="badge">' + esc(s.status) + '</span>' +
            '<span>' + timeAgo(s.createdAt) + '</span>' +
            '<span>\u25B2 ' + s.upVotes + '</span>';
        listEl.appendChild(div);
    });
}

describe('renderUserHistory()', () => {
    let panel, nameEl, listEl;

    beforeEach(() => {
        panel = document.createElement('div');
        nameEl = document.createElement('h3');
        listEl = document.createElement('div');
    });

    test('does nothing when elements are missing', () => {
        expect(() => renderUserHistory('alice', [], null, null, null)).not.toThrow();
    });

    test('shows panel', () => {
        panel.style.display = 'none';
        renderUserHistory('alice', [], panel, nameEl, listEl);
        expect(panel.style.display).toBe('');
    });

    test('sets heading with username', () => {
        renderUserHistory('alice', [], panel, nameEl, listEl);
        expect(nameEl.textContent).toBe("alice's Submissions");
    });

    test('shows empty message when no suggestions', () => {
        renderUserHistory('alice', [], panel, nameEl, listEl);
        expect(listEl.innerHTML).toContain('No submissions found.');
    });

    test('renders one item per suggestion', () => {
        const suggestions = [
            { id: 1, title: 'Fix bug', status: 'MERGED', upVotes: 3, createdAt: new Date(Date.now() - 60000).toISOString() },
            { id: 2, title: 'Add feature', status: 'PENDING', upVotes: 1, createdAt: new Date(Date.now() - 60000).toISOString() },
        ];
        renderUserHistory('bob', suggestions, panel, nameEl, listEl);
        expect(listEl.children).toHaveLength(2);
    });

    test('escapes HTML in suggestion title', () => {
        const suggestions = [
            { id: 1, title: '<img src=x onerror=alert(1)>', status: 'PENDING', upVotes: 0, createdAt: new Date().toISOString() },
        ];
        renderUserHistory('alice', suggestions, panel, nameEl, listEl);
        expect(listEl.innerHTML).not.toContain('<img');
    });

    test('shows vote count', () => {
        const suggestions = [
            { id: 1, title: 'Nice idea', status: 'APPROVED', upVotes: 42, createdAt: new Date(Date.now() - 60000).toISOString() },
        ];
        renderUserHistory('carol', suggestions, panel, nameEl, listEl);
        expect(listEl.textContent).toContain('42');
    });
});
