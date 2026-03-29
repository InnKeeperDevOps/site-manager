/**
 * Tests for Task 5: draft feature UI — Save as Draft, My Drafts view,
 * edit draft modal, and submit draft flow.
 */

// ---------------------------------------------------------------------------
// Minimal app factory
// ---------------------------------------------------------------------------

function makeApp(overrides = {}) {
    const base = {
        state: {
            loggedIn: false,
            username: '',
            role: '',
            permissions: [],
            settings: {},
            currentSuggestion: null,
            ws: null,
            myDraftsMode: false,
            listFilters: { search: '', status: '', priority: '', sortBy: 'created', sortDir: 'desc' },
        },

        showToast(message) {
            let container = document.getElementById('toastContainer');
            if (!container) {
                container = document.createElement('div');
                container.id = 'toastContainer';
                document.body.appendChild(container);
            }
            const toast = document.createElement('div');
            toast.textContent = message;
            container.appendChild(toast);
        },

        navigate: jest.fn(),
        loadSuggestions: jest.fn(),
        loadMyDrafts: jest.fn(),

        updateSaveAsDraftBtn() {
            const btn = document.getElementById('saveAsDraftBtn');
            if (!btn) return;
            btn.style.display = this.state.loggedIn ? '' : 'none';
        },

        updateMyDraftsBtn() {
            const btn = document.getElementById('myDraftsBtn');
            if (!btn) return;
            btn.style.display = this.state.loggedIn ? '' : 'none';
        },

        async api(path, opts) { return {}; },

        esc(s) {
            if (!s) return '';
            const d = document.createElement('div');
            d.textContent = s;
            return d.innerHTML;
        },

        timeAgo() { return 'just now'; },

        async saveAsDraft(e) {
            e.preventDefault();
            const data = await this.api('/suggestions', {
                method: 'POST',
                body: JSON.stringify({
                    title: document.getElementById('createTitle').value,
                    description: document.getElementById('createDescription').value,
                    priority: document.getElementById('createPriority').value || 'MEDIUM',
                    isDraft: true
                })
            });
            if (data.error) {
                this.showToast(data.error);
                return;
            }
            document.getElementById('createForm').reset();
            this.showToast('Draft saved.');
            this.navigate('list');
        },

        showMyDrafts() {
            this.state.myDraftsMode = true;
            const btn = document.getElementById('myDraftsBtn');
            if (btn) btn.textContent = 'All Suggestions';
            if (btn) btn.onclick = () => this.showAllSuggestions();
            this.loadMyDrafts();
        },

        showAllSuggestions() {
            this.state.myDraftsMode = false;
            const btn = document.getElementById('myDraftsBtn');
            if (btn) btn.textContent = 'My Drafts';
            if (btn) btn.onclick = () => this.showMyDrafts();
            this.loadSuggestions();
        },

        renderDraftCards(drafts) {
            return drafts.map(s => {
                const priorityLabel = s.priority || 'MEDIUM';
                return `
                <div class="card suggestion-item" data-suggestion-id="${s.id}">
                    <div class="suggestion-title">${this.esc(s.title)}</div>
                    <div style="display:flex;gap:0.4rem">
                        <span class="priority-badge priority-${priorityLabel}">${priorityLabel}</span>
                        <span class="status-badge">DRAFT</span>
                    </div>
                    <div class="suggestion-quick-actions">
                        <button class="btn btn-outline btn-sm" onclick="app.openEditDraftModal(${s.id})">Edit</button>
                        <button class="btn btn-primary btn-sm" onclick="app.submitDraftConfirm(${s.id})">Submit</button>
                    </div>
                </div>`;
            }).join('');
        },

        openEditDraftModal(id) {
            const modal = document.getElementById('editDraftModal');
            if (!modal) return;
            this.api('/suggestions/' + id).then(s => {
                if (s.error) { this.showToast(s.error); return; }
                document.getElementById('editDraftId').value = id;
                document.getElementById('editDraftTitle').value = s.title || '';
                document.getElementById('editDraftDescription').value = s.description || '';
                document.getElementById('editDraftPriority').value = s.priority || 'MEDIUM';
                modal.style.display = '';
            });
        },

        closeEditDraftModal() {
            const modal = document.getElementById('editDraftModal');
            if (modal) modal.style.display = 'none';
        },

        async saveEditDraft() {
            const id = document.getElementById('editDraftId').value;
            if (!id) return;
            const data = await this.api('/suggestions/' + id + '/draft', {
                method: 'PATCH',
                body: JSON.stringify({
                    title: document.getElementById('editDraftTitle').value,
                    description: document.getElementById('editDraftDescription').value,
                    priority: document.getElementById('editDraftPriority').value
                })
            });
            if (data.error) {
                this.showToast(data.error);
                return;
            }
            this.closeEditDraftModal();
            this.showToast('Draft updated.');
            this.loadMyDrafts();
        },

        async submitDraftConfirm(id) {
            if (!confirm('Submit this draft? It will be sent for review.')) return;
            const data = await this.api('/suggestions/' + id + '/submit', { method: 'POST' });
            if (data.error) {
                this.showToast(data.error);
                return;
            }
            this.showToast('Suggestion submitted.');
            this.showAllSuggestions();
            this.navigate('detail', data.id || id);
        },

        ...overrides,
    };

    // Bind all methods
    for (const key of Object.keys(base)) {
        if (typeof base[key] === 'function' && key !== 'navigate' && key !== 'loadSuggestions' && key !== 'loadMyDrafts') {
            base[key] = base[key].bind(base);
        }
    }

    return base;
}

// ---------------------------------------------------------------------------
// DOM helpers
// ---------------------------------------------------------------------------

function setupListDOM() {
    document.body.innerHTML = `
        <div id="listView" class="view">
            <button id="myDraftsBtn" style="display:none">My Drafts</button>
            <button id="newSuggestionBtn">+ New Suggestion</button>
            <div id="suggestionList"></div>
        </div>
    `;
}

function setupCreateDOM() {
    document.body.innerHTML = `
        <div id="createView" class="view">
            <form id="createForm">
                <input id="createTitle" value="My Draft Title">
                <textarea id="createDescription">Draft description</textarea>
                <select id="createPriority"><option value="MEDIUM">Medium</option><option value="HIGH">High</option></select>
            </form>
            <button id="saveAsDraftBtn" style="display:none">Save as Draft</button>
        </div>
    `;
}

function setupEditDraftModalDOM() {
    document.body.innerHTML = `
        <div id="editDraftModal" style="display:none">
            <input id="editDraftId" type="hidden">
            <input id="editDraftTitle">
            <textarea id="editDraftDescription"></textarea>
            <select id="editDraftPriority">
                <option value="MEDIUM">Medium</option>
                <option value="HIGH">High</option>
                <option value="LOW">Low</option>
            </select>
        </div>
        <div id="suggestionList"></div>
    `;
}

afterEach(() => {
    jest.restoreAllMocks();
    document.body.innerHTML = '';
});

// ---------------------------------------------------------------------------
// updateSaveAsDraftBtn()
// ---------------------------------------------------------------------------

describe('updateSaveAsDraftBtn()', () => {
    test('shows Save as Draft button when user is logged in', () => {
        setupCreateDOM();
        const app = makeApp({ state: { loggedIn: true, myDraftsMode: false } });
        app.updateSaveAsDraftBtn();
        expect(document.getElementById('saveAsDraftBtn').style.display).toBe('');
    });

    test('hides Save as Draft button when user is not logged in', () => {
        setupCreateDOM();
        const app = makeApp({ state: { loggedIn: false, myDraftsMode: false } });
        app.updateSaveAsDraftBtn();
        expect(document.getElementById('saveAsDraftBtn').style.display).toBe('none');
    });

    test('does nothing gracefully when button is absent', () => {
        const app = makeApp();
        expect(() => app.updateSaveAsDraftBtn()).not.toThrow();
    });
});

// ---------------------------------------------------------------------------
// updateMyDraftsBtn()
// ---------------------------------------------------------------------------

describe('updateMyDraftsBtn()', () => {
    test('shows My Drafts button when user is logged in', () => {
        setupListDOM();
        const app = makeApp({ state: { loggedIn: true, myDraftsMode: false } });
        app.updateMyDraftsBtn();
        expect(document.getElementById('myDraftsBtn').style.display).toBe('');
    });

    test('hides My Drafts button when user is not logged in', () => {
        setupListDOM();
        const app = makeApp({ state: { loggedIn: false, myDraftsMode: false } });
        app.updateMyDraftsBtn();
        expect(document.getElementById('myDraftsBtn').style.display).toBe('none');
    });
});

// ---------------------------------------------------------------------------
// saveAsDraft()
// ---------------------------------------------------------------------------

describe('saveAsDraft()', () => {
    test('calls POST /suggestions with isDraft=true', async () => {
        setupCreateDOM();
        const mockApi = jest.fn().mockResolvedValue({ id: 7 });
        const app = makeApp({ api: mockApi });
        const fakeEvent = { preventDefault: jest.fn() };
        await app.saveAsDraft(fakeEvent);
        expect(mockApi).toHaveBeenCalledWith('/suggestions', expect.objectContaining({ method: 'POST' }));
        const body = JSON.parse(mockApi.mock.calls[0][1].body);
        expect(body.isDraft).toBe(true);
    });

    test('shows "Draft saved." toast on success', async () => {
        setupCreateDOM();
        const app = makeApp({ api: jest.fn().mockResolvedValue({ id: 7 }) });
        const fakeEvent = { preventDefault: jest.fn() };
        await app.saveAsDraft(fakeEvent);
        const container = document.getElementById('toastContainer');
        expect(container.textContent).toContain('Draft saved.');
    });

    test('navigates to list on success', async () => {
        setupCreateDOM();
        const app = makeApp({ api: jest.fn().mockResolvedValue({ id: 7 }) });
        const fakeEvent = { preventDefault: jest.fn() };
        await app.saveAsDraft(fakeEvent);
        expect(app.navigate).toHaveBeenCalledWith('list');
    });

    test('resets the form on success', async () => {
        setupCreateDOM();
        const app = makeApp({ api: jest.fn().mockResolvedValue({ id: 7 }) });
        const fakeEvent = { preventDefault: jest.fn() };
        const resetSpy = jest.spyOn(document.getElementById('createForm'), 'reset');
        await app.saveAsDraft(fakeEvent);
        expect(resetSpy).toHaveBeenCalled();
    });

    test('shows error toast when API returns error', async () => {
        setupCreateDOM();
        const mockShowToast = jest.fn();
        const app = makeApp({ api: jest.fn().mockResolvedValue({ error: 'Not logged in' }), showToast: mockShowToast });
        const fakeEvent = { preventDefault: jest.fn() };
        await app.saveAsDraft(fakeEvent);
        expect(mockShowToast).toHaveBeenCalledWith('Not logged in');
        expect(app.navigate).not.toHaveBeenCalled();
    });

    test('sends title, description, and priority in body', async () => {
        setupCreateDOM();
        document.getElementById('createTitle').value = 'Draft title';
        document.getElementById('createDescription').value = 'Draft body';
        document.querySelector('#createPriority option[value="HIGH"]').selected = true;
        const mockApi = jest.fn().mockResolvedValue({ id: 3 });
        const app = makeApp({ api: mockApi });
        await app.saveAsDraft({ preventDefault: jest.fn() });
        const body = JSON.parse(mockApi.mock.calls[0][1].body);
        expect(body.title).toBe('Draft title');
        expect(body.description).toBe('Draft body');
        expect(body.priority).toBe('HIGH');
    });
});

// ---------------------------------------------------------------------------
// showMyDrafts() / showAllSuggestions()
// ---------------------------------------------------------------------------

describe('showMyDrafts()', () => {
    test('sets myDraftsMode to true', () => {
        setupListDOM();
        const app = makeApp();
        app.showMyDrafts();
        expect(app.state.myDraftsMode).toBe(true);
    });

    test('changes the My Drafts button text to "All Suggestions"', () => {
        setupListDOM();
        const app = makeApp();
        app.showMyDrafts();
        expect(document.getElementById('myDraftsBtn').textContent).toBe('All Suggestions');
    });

    test('calls loadMyDrafts()', () => {
        setupListDOM();
        const mockLoadMyDrafts = jest.fn();
        const app = makeApp({ loadMyDrafts: mockLoadMyDrafts });
        app.showMyDrafts();
        expect(mockLoadMyDrafts).toHaveBeenCalled();
    });
});

describe('showAllSuggestions()', () => {
    test('sets myDraftsMode to false', () => {
        setupListDOM();
        const app = makeApp({ state: { myDraftsMode: true, loggedIn: true } });
        app.showAllSuggestions();
        expect(app.state.myDraftsMode).toBe(false);
    });

    test('restores My Drafts button text', () => {
        setupListDOM();
        const btn = document.getElementById('myDraftsBtn');
        btn.textContent = 'All Suggestions';
        const app = makeApp({ state: { myDraftsMode: true, loggedIn: true } });
        app.showAllSuggestions();
        expect(btn.textContent).toBe('My Drafts');
    });

    test('calls loadSuggestions()', () => {
        setupListDOM();
        const mockLoadSuggestions = jest.fn();
        const app = makeApp({ loadSuggestions: mockLoadSuggestions });
        app.showAllSuggestions();
        expect(mockLoadSuggestions).toHaveBeenCalled();
    });
});

// ---------------------------------------------------------------------------
// renderDraftCards()
// ---------------------------------------------------------------------------

describe('renderDraftCards()', () => {
    test('renders a card for each draft', () => {
        const app = makeApp();
        const drafts = [
            { id: 1, title: 'Draft One', priority: 'HIGH', createdAt: null, authorName: 'alice' },
            { id: 2, title: 'Draft Two', priority: 'LOW', createdAt: null, authorName: 'bob' },
        ];
        const html = app.renderDraftCards(drafts);
        const div = document.createElement('div');
        div.innerHTML = html;
        expect(div.querySelectorAll('.suggestion-item').length).toBe(2);
    });

    test('each draft card has Edit and Submit buttons', () => {
        const app = makeApp();
        const drafts = [{ id: 5, title: 'Test', priority: 'MEDIUM', createdAt: null, authorName: 'x' }];
        const html = app.renderDraftCards(drafts);
        const div = document.createElement('div');
        div.innerHTML = html;
        const buttons = div.querySelectorAll('button');
        const texts = Array.from(buttons).map(b => b.textContent);
        expect(texts).toContain('Edit');
        expect(texts).toContain('Submit');
    });

    test('shows DRAFT status badge', () => {
        const app = makeApp();
        const drafts = [{ id: 3, title: 'A', priority: 'MEDIUM', createdAt: null, authorName: 'y' }];
        const html = app.renderDraftCards(drafts);
        expect(html).toContain('DRAFT');
    });

    test('escapes title to prevent XSS', () => {
        const app = makeApp();
        const drafts = [{ id: 4, title: '<script>evil()</script>', priority: 'MEDIUM', createdAt: null, authorName: 'z' }];
        const html = app.renderDraftCards(drafts);
        const div = document.createElement('div');
        div.innerHTML = html;
        expect(div.querySelector('script')).toBeNull();
    });
});

// ---------------------------------------------------------------------------
// openEditDraftModal()
// ---------------------------------------------------------------------------

describe('openEditDraftModal()', () => {
    test('populates modal fields with fetched draft data', async () => {
        setupEditDraftModalDOM();
        const mockApi = jest.fn().mockResolvedValue({ id: 10, title: 'Old Title', description: 'Old Desc', priority: 'LOW' });
        const app = makeApp({ api: mockApi });
        await app.openEditDraftModal(10);
        expect(document.getElementById('editDraftId').value).toBe('10');
        expect(document.getElementById('editDraftTitle').value).toBe('Old Title');
        expect(document.getElementById('editDraftDescription').value).toBe('Old Desc');
        expect(document.getElementById('editDraftPriority').value).toBe('LOW');
    });

    test('shows the modal', async () => {
        setupEditDraftModalDOM();
        const mockApi = jest.fn().mockResolvedValue({ id: 10, title: 'T', description: 'D', priority: 'MEDIUM' });
        const app = makeApp({ api: mockApi });
        await app.openEditDraftModal(10);
        expect(document.getElementById('editDraftModal').style.display).toBe('');
    });

    test('shows toast when API returns error', async () => {
        setupEditDraftModalDOM();
        const mockShowToast = jest.fn();
        const mockApi = jest.fn().mockResolvedValue({ error: 'Not found' });
        const app = makeApp({ api: mockApi, showToast: mockShowToast });
        await app.openEditDraftModal(99);
        expect(mockShowToast).toHaveBeenCalledWith('Not found');
    });

    test('does nothing gracefully when modal element is absent', () => {
        const mockApi = jest.fn().mockResolvedValue({ id: 1, title: 'T', description: 'D', priority: 'MEDIUM' });
        const app = makeApp({ api: mockApi });
        expect(() => app.openEditDraftModal(1)).not.toThrow();
    });
});

// ---------------------------------------------------------------------------
// closeEditDraftModal()
// ---------------------------------------------------------------------------

describe('closeEditDraftModal()', () => {
    test('hides the modal', () => {
        setupEditDraftModalDOM();
        document.getElementById('editDraftModal').style.display = '';
        const app = makeApp();
        app.closeEditDraftModal();
        expect(document.getElementById('editDraftModal').style.display).toBe('none');
    });
});

// ---------------------------------------------------------------------------
// saveEditDraft()
// ---------------------------------------------------------------------------

describe('saveEditDraft()', () => {
    test('calls PATCH /suggestions/{id}/draft with form values', async () => {
        setupEditDraftModalDOM();
        document.getElementById('editDraftId').value = '12';
        document.getElementById('editDraftTitle').value = 'New Title';
        document.getElementById('editDraftDescription').value = 'New Desc';
        document.getElementById('editDraftPriority').value = 'HIGH';
        const mockApi = jest.fn().mockResolvedValue({ id: 12, title: 'New Title' });
        const app = makeApp({ api: mockApi });
        await app.saveEditDraft();
        expect(mockApi).toHaveBeenCalledWith('/suggestions/12/draft', expect.objectContaining({ method: 'PATCH' }));
        const body = JSON.parse(mockApi.mock.calls[0][1].body);
        expect(body.title).toBe('New Title');
        expect(body.description).toBe('New Desc');
        expect(body.priority).toBe('HIGH');
    });

    test('shows "Draft updated." toast on success', async () => {
        setupEditDraftModalDOM();
        document.getElementById('editDraftId').value = '12';
        const mockShowToast = jest.fn();
        const app = makeApp({ api: jest.fn().mockResolvedValue({ id: 12 }), showToast: mockShowToast, loadMyDrafts: jest.fn() });
        await app.saveEditDraft();
        expect(mockShowToast).toHaveBeenCalledWith('Draft updated.');
    });

    test('closes the modal on success', async () => {
        setupEditDraftModalDOM();
        document.getElementById('editDraftId').value = '12';
        document.getElementById('editDraftModal').style.display = '';
        const app = makeApp({ api: jest.fn().mockResolvedValue({ id: 12 }), loadMyDrafts: jest.fn() });
        await app.saveEditDraft();
        expect(document.getElementById('editDraftModal').style.display).toBe('none');
    });

    test('shows error toast when API returns error', async () => {
        setupEditDraftModalDOM();
        document.getElementById('editDraftId').value = '12';
        const mockShowToast = jest.fn();
        const app = makeApp({ api: jest.fn().mockResolvedValue({ error: 'Forbidden' }), showToast: mockShowToast });
        await app.saveEditDraft();
        expect(mockShowToast).toHaveBeenCalledWith('Forbidden');
    });

    test('does nothing when editDraftId is empty', async () => {
        setupEditDraftModalDOM();
        document.getElementById('editDraftId').value = '';
        const mockApi = jest.fn();
        const app = makeApp({ api: mockApi });
        await app.saveEditDraft();
        expect(mockApi).not.toHaveBeenCalled();
    });
});

// ---------------------------------------------------------------------------
// submitDraftConfirm()
// ---------------------------------------------------------------------------

describe('submitDraftConfirm()', () => {
    beforeEach(() => {
        jest.spyOn(window, 'confirm').mockReturnValue(true);
    });

    test('calls POST /suggestions/{id}/submit', async () => {
        const mockApi = jest.fn().mockResolvedValue({ id: 15 });
        const app = makeApp({ api: mockApi });
        await app.submitDraftConfirm(15);
        expect(mockApi).toHaveBeenCalledWith('/suggestions/15/submit', expect.objectContaining({ method: 'POST' }));
    });

    test('shows "Suggestion submitted." toast on success', async () => {
        const mockShowToast = jest.fn();
        const app = makeApp({ api: jest.fn().mockResolvedValue({ id: 15 }), showToast: mockShowToast });
        await app.submitDraftConfirm(15);
        expect(mockShowToast).toHaveBeenCalledWith('Suggestion submitted.');
    });

    test('navigates to detail view of submitted suggestion on success', async () => {
        const app = makeApp({ api: jest.fn().mockResolvedValue({ id: 15 }) });
        await app.submitDraftConfirm(15);
        expect(app.navigate).toHaveBeenCalledWith('detail', 15);
    });

    test('falls back to passed id when response has no id', async () => {
        const app = makeApp({ api: jest.fn().mockResolvedValue({}) });
        await app.submitDraftConfirm(99);
        expect(app.navigate).toHaveBeenCalledWith('detail', 99);
    });

    test('shows error toast when API returns error', async () => {
        const mockShowToast = jest.fn();
        const app = makeApp({ api: jest.fn().mockResolvedValue({ error: 'Not your draft' }), showToast: mockShowToast });
        await app.submitDraftConfirm(5);
        expect(mockShowToast).toHaveBeenCalledWith('Not your draft');
        expect(app.navigate).not.toHaveBeenCalled();
    });

    test('does nothing when user cancels confirmation', async () => {
        window.confirm.mockReturnValue(false);
        const mockApi = jest.fn();
        const app = makeApp({ api: mockApi });
        await app.submitDraftConfirm(5);
        expect(mockApi).not.toHaveBeenCalled();
        expect(app.navigate).not.toHaveBeenCalled();
    });
});
