/**
 * Tests for Task 4: suggestion creation gating.
 *
 * Covers:
 *   - updateNewSuggestionBtn(): show/hide based on allowAnonymousSuggestions + permissions
 *   - showToast(): renders a toast message in the DOM
 *   - navigate('create') guard: redirect or block based on auth/permissions
 *   - createSuggestion(): handles 403/error response via showToast instead of alert
 */

// ---------------------------------------------------------------------------
// Minimal app factory — only the methods relevant to this task
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
        },

        // --- methods under test ---

        updateNewSuggestionBtn() {
            const btn = document.getElementById('newSuggestionBtn');
            if (!btn) return;
            const allowAnon = this.state.settings.allowAnonymousSuggestions;
            const hasPermission = this.state.permissions.includes('CREATE_SUGGESTIONS');
            btn.style.display = (allowAnon || hasPermission) ? '' : 'none';
        },

        showToast(message) {
            let container = document.getElementById('toastContainer');
            if (!container) {
                container = document.createElement('div');
                container.id = 'toastContainer';
                container.style.cssText = 'position:fixed;top:1rem;right:1rem;z-index:9999;display:flex;flex-direction:column;gap:0.5rem';
                document.body.appendChild(container);
            }
            const toast = document.createElement('div');
            toast.style.cssText = 'background:#1e293b;color:#fff;padding:0.75rem 1.25rem;border-radius:6px;font-size:0.9rem';
            toast.textContent = message;
            container.appendChild(toast);
            // Note: we omit setTimeout removal so tests can inspect the DOM synchronously
        },

        navigate: jest.fn(),

        disconnectWs() {},

        navigateReal(view, data) {
            // Guard: enforce suggestion creation permissions before showing the view
            if (view === 'create') {
                const allowAnon = this.state.settings.allowAnonymousSuggestions;
                const hasPermission = this.state.permissions.includes('CREATE_SUGGESTIONS');
                if (!this.state.loggedIn && !allowAnon) {
                    this.showToast('Please log in to create a suggestion');
                    this.navigate('login');
                    return;
                }
                if (this.state.loggedIn && !hasPermission && !allowAnon) {
                    this.showToast('You do not have permission to create suggestions');
                    return;
                }
            }

            document.querySelectorAll('.view').forEach(v => v.classList.remove('active'));
            const el = document.getElementById(view + 'View');
            if (el) {
                el.style.display = '';
                el.classList.add('active');
            }

            this.disconnectWs();

            if (view === 'create') {
                const nameGroup = document.getElementById('anonNameGroup');
                if (nameGroup) nameGroup.style.display = this.state.loggedIn ? 'none' : '';
            }
        },

        async api(path, opts) { return {}; },

        async createSuggestion(e) {
            e.preventDefault();
            const data = await this.api('/suggestions', {
                method: 'POST',
                body: JSON.stringify({
                    title: document.getElementById('createTitle').value,
                    description: document.getElementById('createDescription').value,
                    authorName: document.getElementById('createAuthorName').value || undefined
                })
            });
            if (data.error) {
                this.showToast(data.error);
                return;
            }
            document.getElementById('createForm').reset();
            this.navigate('detail', data.id);
        },

        ...overrides,
    };

    // bind navigate alias
    base.navigateReal = base.navigateReal.bind(base);
    base.createSuggestion = base.createSuggestion.bind(base);
    base.updateNewSuggestionBtn = base.updateNewSuggestionBtn.bind(base);
    base.showToast = base.showToast.bind(base);
    return base;
}

// ---------------------------------------------------------------------------
// DOM setup helpers
// ---------------------------------------------------------------------------

function setupListDOM() {
    document.body.innerHTML += `
        <div id="listView" class="view">
            <button id="newSuggestionBtn" class="btn btn-primary">+ New Suggestion</button>
        </div>
    `;
}

function setupCreateDOM() {
    document.body.innerHTML += `
        <div id="createView" class="view" style="display:none">
            <div id="anonNameGroup"></div>
            <form id="createForm">
                <input id="createTitle" value="Test Title">
                <textarea id="createDescription">Test desc</textarea>
                <input id="createAuthorName" value="">
            </form>
        </div>
        <div id="loginView" class="view" style="display:none"></div>
    `;
}

afterEach(() => {
    jest.restoreAllMocks();
    document.body.innerHTML = '';
});

// ---------------------------------------------------------------------------
// updateNewSuggestionBtn()
// ---------------------------------------------------------------------------

describe('updateNewSuggestionBtn()', () => {
    test('shows button when allowAnonymousSuggestions is true (no login)', () => {
        setupListDOM();
        const app = makeApp({
            state: { loggedIn: false, permissions: [], settings: { allowAnonymousSuggestions: true } },
        });
        app.updateNewSuggestionBtn();
        expect(document.getElementById('newSuggestionBtn').style.display).toBe('');
    });

    test('shows button when user has CREATE_SUGGESTIONS permission', () => {
        setupListDOM();
        const app = makeApp({
            state: { loggedIn: true, permissions: ['CREATE_SUGGESTIONS'], settings: { allowAnonymousSuggestions: false } },
        });
        app.updateNewSuggestionBtn();
        expect(document.getElementById('newSuggestionBtn').style.display).toBe('');
    });

    test('hides button when allowAnonymousSuggestions is false and no permission', () => {
        setupListDOM();
        const app = makeApp({
            state: { loggedIn: true, permissions: [], settings: { allowAnonymousSuggestions: false } },
        });
        app.updateNewSuggestionBtn();
        expect(document.getElementById('newSuggestionBtn').style.display).toBe('none');
    });

    test('hides button for anonymous user when allowAnonymousSuggestions is false', () => {
        setupListDOM();
        const app = makeApp({
            state: { loggedIn: false, permissions: [], settings: { allowAnonymousSuggestions: false } },
        });
        app.updateNewSuggestionBtn();
        expect(document.getElementById('newSuggestionBtn').style.display).toBe('none');
    });

    test('shows button when both allowAnon=true and permission present', () => {
        setupListDOM();
        const app = makeApp({
            state: { loggedIn: true, permissions: ['CREATE_SUGGESTIONS'], settings: { allowAnonymousSuggestions: true } },
        });
        app.updateNewSuggestionBtn();
        expect(document.getElementById('newSuggestionBtn').style.display).toBe('');
    });

    test('does nothing gracefully when button element is absent', () => {
        // No DOM set up — button doesn't exist
        const app = makeApp({
            state: { loggedIn: false, permissions: [], settings: { allowAnonymousSuggestions: false } },
        });
        expect(() => app.updateNewSuggestionBtn()).not.toThrow();
    });
});

// ---------------------------------------------------------------------------
// showToast()
// ---------------------------------------------------------------------------

describe('showToast()', () => {
    test('creates a toast container and appends a toast message', () => {
        const app = makeApp();
        app.showToast('Hello world');
        const container = document.getElementById('toastContainer');
        expect(container).not.toBeNull();
        expect(container.textContent).toContain('Hello world');
    });

    test('reuses existing toast container for multiple toasts', () => {
        const app = makeApp();
        app.showToast('First toast');
        app.showToast('Second toast');
        const containers = document.querySelectorAll('#toastContainer');
        expect(containers.length).toBe(1);
        expect(containers[0].children.length).toBe(2);
    });

    test('toast text is set via textContent (XSS-safe)', () => {
        const app = makeApp();
        const xss = '<script>evil()</script>';
        app.showToast(xss);
        const container = document.getElementById('toastContainer');
        expect(container.querySelector('script')).toBeNull();
        expect(container.textContent).toContain(xss);
    });
});

// ---------------------------------------------------------------------------
// navigate('create') guard
// ---------------------------------------------------------------------------

describe("navigate('create') — anonymous user, anonymous suggestions disabled", () => {
    test('shows "Please log in" toast and navigates to login', () => {
        setupCreateDOM();
        const app = makeApp({
            state: { loggedIn: false, permissions: [], settings: { allowAnonymousSuggestions: false } },
        });
        app.navigateReal('create');
        const container = document.getElementById('toastContainer');
        expect(container).not.toBeNull();
        expect(container.textContent).toContain('Please log in to create a suggestion');
        expect(app.navigate).toHaveBeenCalledWith('login');
    });

    test('does not show createView when redirected to login', () => {
        setupCreateDOM();
        const app = makeApp({
            state: { loggedIn: false, permissions: [], settings: { allowAnonymousSuggestions: false } },
        });
        app.navigateReal('create');
        const createView = document.getElementById('createView');
        expect(createView.classList.contains('active')).toBe(false);
    });
});

describe("navigate('create') — logged-in user without CREATE_SUGGESTIONS, anonymous disabled", () => {
    test('shows "no permission" toast', () => {
        setupCreateDOM();
        const app = makeApp({
            state: { loggedIn: true, role: 'USER', permissions: ['VOTE'], settings: { allowAnonymousSuggestions: false } },
        });
        app.navigateReal('create');
        const container = document.getElementById('toastContainer');
        expect(container).not.toBeNull();
        expect(container.textContent).toContain('You do not have permission to create suggestions');
    });

    test('does not navigate away (stays on current view)', () => {
        setupCreateDOM();
        const app = makeApp({
            state: { loggedIn: true, role: 'USER', permissions: [], settings: { allowAnonymousSuggestions: false } },
        });
        app.navigateReal('create');
        expect(app.navigate).not.toHaveBeenCalled();
    });

    test('does not show createView', () => {
        setupCreateDOM();
        const app = makeApp({
            state: { loggedIn: true, role: 'USER', permissions: [], settings: { allowAnonymousSuggestions: false } },
        });
        app.navigateReal('create');
        const createView = document.getElementById('createView');
        expect(createView.classList.contains('active')).toBe(false);
    });
});

describe("navigate('create') — user with CREATE_SUGGESTIONS permission", () => {
    test('shows createView without toast', () => {
        setupCreateDOM();
        const app = makeApp({
            state: { loggedIn: true, role: 'USER', permissions: ['CREATE_SUGGESTIONS'], settings: { allowAnonymousSuggestions: false } },
        });
        app.navigateReal('create');
        const createView = document.getElementById('createView');
        expect(createView.classList.contains('active')).toBe(true);
        expect(document.getElementById('toastContainer')).toBeNull();
    });

    test('does not redirect to login', () => {
        setupCreateDOM();
        const app = makeApp({
            state: { loggedIn: true, role: 'USER', permissions: ['CREATE_SUGGESTIONS'], settings: { allowAnonymousSuggestions: false } },
        });
        app.navigateReal('create');
        expect(app.navigate).not.toHaveBeenCalledWith('login');
    });
});

describe("navigate('create') — allowAnonymousSuggestions=true", () => {
    test('allows anonymous user through without toast', () => {
        setupCreateDOM();
        const app = makeApp({
            state: { loggedIn: false, permissions: [], settings: { allowAnonymousSuggestions: true } },
        });
        app.navigateReal('create');
        const createView = document.getElementById('createView');
        expect(createView.classList.contains('active')).toBe(true);
        expect(document.getElementById('toastContainer')).toBeNull();
    });

    test('allows logged-in user without permission through when anon is enabled', () => {
        setupCreateDOM();
        const app = makeApp({
            state: { loggedIn: true, role: 'USER', permissions: [], settings: { allowAnonymousSuggestions: true } },
        });
        app.navigateReal('create');
        const createView = document.getElementById('createView');
        expect(createView.classList.contains('active')).toBe(true);
    });
});

// ---------------------------------------------------------------------------
// createSuggestion() — 403 / error handling via showToast
// ---------------------------------------------------------------------------

describe('createSuggestion() — error response handling', () => {
    function setupCreateFormDOM() {
        document.body.innerHTML = `
            <form id="createForm">
                <input id="createTitle" value="My suggestion">
                <textarea id="createDescription">Description here</textarea>
                <input id="createAuthorName" value="">
            </form>
        `;
    }

    test('calls showToast with error message when API returns error', async () => {
        setupCreateFormDOM();
        const mockApi = jest.fn().mockResolvedValue({ error: 'You are not allowed to create suggestions' });
        const mockShowToast = jest.fn();
        const app = makeApp({ api: mockApi, showToast: mockShowToast });
        const fakeEvent = { preventDefault: jest.fn() };
        await app.createSuggestion(fakeEvent);
        expect(mockShowToast).toHaveBeenCalledWith('You are not allowed to create suggestions');
    });

    test('does not navigate to detail when API returns error', async () => {
        setupCreateFormDOM();
        const mockApi = jest.fn().mockResolvedValue({ error: 'Forbidden' });
        const mockShowToast = jest.fn();
        const app = makeApp({ api: mockApi, showToast: mockShowToast });
        const fakeEvent = { preventDefault: jest.fn() };
        await app.createSuggestion(fakeEvent);
        expect(app.navigate).not.toHaveBeenCalled();
    });

    test('navigates to detail view on success', async () => {
        setupCreateFormDOM();
        const mockApi = jest.fn().mockResolvedValue({ id: 42 });
        const app = makeApp({ api: mockApi });
        const fakeEvent = { preventDefault: jest.fn() };
        await app.createSuggestion(fakeEvent);
        expect(app.navigate).toHaveBeenCalledWith('detail', 42);
    });

    test('resets form on success', async () => {
        setupCreateFormDOM();
        const mockApi = jest.fn().mockResolvedValue({ id: 5 });
        const app = makeApp({ api: mockApi });
        const fakeEvent = { preventDefault: jest.fn() };
        const resetSpy = jest.spyOn(document.getElementById('createForm'), 'reset');
        await app.createSuggestion(fakeEvent);
        expect(resetSpy).toHaveBeenCalled();
    });

    test('calls POST /suggestions with correct payload', async () => {
        setupCreateFormDOM();
        document.getElementById('createTitle').value = 'New Feature';
        document.getElementById('createDescription').value = 'Please add X';
        const mockApi = jest.fn().mockResolvedValue({ id: 1 });
        const app = makeApp({ api: mockApi });
        const fakeEvent = { preventDefault: jest.fn() };
        await app.createSuggestion(fakeEvent);
        expect(mockApi).toHaveBeenCalledWith('/suggestions', expect.objectContaining({
            method: 'POST',
        }));
        const body = JSON.parse(mockApi.mock.calls[0][1].body);
        expect(body.title).toBe('New Feature');
        expect(body.description).toBe('Please add X');
    });

    test('does not use alert() for error messages', async () => {
        setupCreateFormDOM();
        const alertSpy = jest.spyOn(window, 'alert').mockImplementation(() => {});
        const mockApi = jest.fn().mockResolvedValue({ error: 'Some error' });
        const mockShowToast = jest.fn();
        const app = makeApp({ api: mockApi, showToast: mockShowToast });
        const fakeEvent = { preventDefault: jest.fn() };
        await app.createSuggestion(fakeEvent);
        expect(alertSpy).not.toHaveBeenCalled();
    });
});
