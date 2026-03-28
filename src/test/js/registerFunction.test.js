/**
 * Tests for the navigate('register') support and register(e) method in app.js.
 */

// --- Helpers ---

function makeApp(overrides = {}) {
    return {
        state: {
            loggedIn: false,
            username: '',
            role: '',
        },

        navigate: jest.fn(),
        updateHeader: jest.fn(),

        async api(path, opts) {
            // replaced per-test via overrides
        },

        async register(e) {
            e.preventDefault();
            const username = document.getElementById('registerUsername').value;
            const password = document.getElementById('registerPassword').value;
            const confirmPassword = document.getElementById('registerConfirmPassword').value;

            if (password !== confirmPassword) {
                alert('Passwords do not match');
                return;
            }

            const data = await this.api('/auth/register', {
                method: 'POST',
                body: JSON.stringify({ username, password })
            });

            if (data.error) { alert(data.error); return; }

            if (data.pending) {
                alert('Your account has been created and is pending approval.');
                this.navigate('login');
            } else {
                this.state.loggedIn = true;
                this.state.username = data.username;
                this.state.role = 'USER';
                this.updateHeader();
                this.navigate('list');
            }

            document.getElementById('registerUsername').value = '';
            document.getElementById('registerPassword').value = '';
            document.getElementById('registerConfirmPassword').value = '';
        },

        ...overrides,
    };
}

function setupRegisterForm({ username = '', password = '', confirmPassword = '' } = {}) {
    document.body.innerHTML = `
        <input id="registerUsername" value="${username}" />
        <input id="registerPassword" value="${password}" />
        <input id="registerConfirmPassword" value="${confirmPassword}" />
    `;
}

beforeEach(() => {
    jest.spyOn(window, 'alert').mockImplementation(() => {});
});

afterEach(() => {
    jest.restoreAllMocks();
    document.body.innerHTML = '';
});

// --- navigate('register') tests ---

describe('navigate supports registerView', () => {
    function makeNavigator() {
        return {
            state: { loggedIn: false, settings: {} },

            disconnectWs() {},
            loadSuggestions() {},
            loadSettings() {},
            loadDetail() {},

            navigate(view, data) {
                document.querySelectorAll('.view').forEach(v => v.classList.remove('active'));
                const el = document.getElementById(view + 'View');
                if (el) {
                    el.style.display = '';
                    el.classList.add('active');
                }
            },
        };
    }

    function setupViews() {
        document.body.innerHTML = `
            <div id="loginView" class="view active"></div>
            <div id="registerView" class="view" style="display:none"></div>
            <div id="listView" class="view"></div>
        `;
    }

    test('adds active class to registerView', () => {
        setupViews();
        const nav = makeNavigator();
        nav.navigate('register');
        expect(document.getElementById('registerView').classList.contains('active')).toBe(true);
    });

    test('removes active class from previously active view', () => {
        setupViews();
        const nav = makeNavigator();
        nav.navigate('register');
        expect(document.getElementById('loginView').classList.contains('active')).toBe(false);
    });

    test('clears inline display:none so registerView becomes visible', () => {
        setupViews();
        const nav = makeNavigator();
        nav.navigate('register');
        expect(document.getElementById('registerView').style.display).toBe('');
    });

    test('navigating away from register removes active class', () => {
        setupViews();
        const nav = makeNavigator();
        nav.navigate('register');
        nav.navigate('login');
        expect(document.getElementById('registerView').classList.contains('active')).toBe(false);
        expect(document.getElementById('loginView').classList.contains('active')).toBe(true);
    });
});

// --- register(e) tests ---

describe('register(e) — password validation', () => {
    test('calls e.preventDefault()', async () => {
        setupRegisterForm({ username: 'alice', password: 'pass1', confirmPassword: 'pass1' });
        const app = makeApp({ api: jest.fn().mockResolvedValue({ username: 'alice', pending: false }) });
        const e = { preventDefault: jest.fn() };
        await app.register(e);
        expect(e.preventDefault).toHaveBeenCalled();
    });

    test('alerts when passwords do not match', async () => {
        setupRegisterForm({ username: 'alice', password: 'abc', confirmPassword: 'xyz' });
        const app = makeApp();
        await app.register({ preventDefault: jest.fn() });
        expect(window.alert).toHaveBeenCalledWith('Passwords do not match');
    });

    test('does not call api when passwords do not match', async () => {
        setupRegisterForm({ username: 'alice', password: 'abc', confirmPassword: 'xyz' });
        const mockApi = jest.fn();
        const app = makeApp({ api: mockApi });
        await app.register({ preventDefault: jest.fn() });
        expect(mockApi).not.toHaveBeenCalled();
    });

    test('does not navigate when passwords do not match', async () => {
        setupRegisterForm({ username: 'alice', password: 'abc', confirmPassword: 'xyz' });
        const app = makeApp();
        await app.register({ preventDefault: jest.fn() });
        expect(app.navigate).not.toHaveBeenCalled();
    });
});

describe('register(e) — API error', () => {
    test('alerts data.error when API returns error', async () => {
        setupRegisterForm({ username: 'alice', password: 'pass', confirmPassword: 'pass' });
        const app = makeApp({ api: jest.fn().mockResolvedValue({ error: 'Username already taken' }) });
        await app.register({ preventDefault: jest.fn() });
        expect(window.alert).toHaveBeenCalledWith('Username already taken');
    });

    test('does not navigate when API returns error', async () => {
        setupRegisterForm({ username: 'alice', password: 'pass', confirmPassword: 'pass' });
        const app = makeApp({ api: jest.fn().mockResolvedValue({ error: 'Username already taken' }) });
        await app.register({ preventDefault: jest.fn() });
        expect(app.navigate).not.toHaveBeenCalled();
    });
});

describe('register(e) — pending approval', () => {
    test('alerts pending approval message', async () => {
        setupRegisterForm({ username: 'alice', password: 'pass', confirmPassword: 'pass' });
        const app = makeApp({
            api: jest.fn().mockResolvedValue({ username: 'alice', pending: true, message: 'Registration submitted and pending admin approval' })
        });
        await app.register({ preventDefault: jest.fn() });
        expect(window.alert).toHaveBeenCalledWith('Your account has been created and is pending approval.');
    });

    test('navigates to login when pending', async () => {
        setupRegisterForm({ username: 'alice', password: 'pass', confirmPassword: 'pass' });
        const app = makeApp({
            api: jest.fn().mockResolvedValue({ username: 'alice', pending: true, message: 'pending' })
        });
        await app.register({ preventDefault: jest.fn() });
        expect(app.navigate).toHaveBeenCalledWith('login');
    });

    test('clears form fields after pending registration', async () => {
        setupRegisterForm({ username: 'alice', password: 'pass', confirmPassword: 'pass' });
        const app = makeApp({
            api: jest.fn().mockResolvedValue({ username: 'alice', pending: true, message: 'pending' })
        });
        await app.register({ preventDefault: jest.fn() });
        expect(document.getElementById('registerUsername').value).toBe('');
        expect(document.getElementById('registerPassword').value).toBe('');
        expect(document.getElementById('registerConfirmPassword').value).toBe('');
    });
});

describe('register(e) — immediate approval', () => {
    test('sets state.loggedIn to true', async () => {
        setupRegisterForm({ username: 'bob', password: 'pass', confirmPassword: 'pass' });
        const app = makeApp({
            api: jest.fn().mockResolvedValue({ username: 'bob', pending: false })
        });
        await app.register({ preventDefault: jest.fn() });
        expect(app.state.loggedIn).toBe(true);
    });

    test('sets state.username from API response', async () => {
        setupRegisterForm({ username: 'bob', password: 'pass', confirmPassword: 'pass' });
        const app = makeApp({
            api: jest.fn().mockResolvedValue({ username: 'bob', pending: false })
        });
        await app.register({ preventDefault: jest.fn() });
        expect(app.state.username).toBe('bob');
    });

    test('sets state.role to USER', async () => {
        setupRegisterForm({ username: 'bob', password: 'pass', confirmPassword: 'pass' });
        const app = makeApp({
            api: jest.fn().mockResolvedValue({ username: 'bob', pending: false })
        });
        await app.register({ preventDefault: jest.fn() });
        expect(app.state.role).toBe('USER');
    });

    test('calls updateHeader', async () => {
        setupRegisterForm({ username: 'bob', password: 'pass', confirmPassword: 'pass' });
        const app = makeApp({
            api: jest.fn().mockResolvedValue({ username: 'bob', pending: false })
        });
        await app.register({ preventDefault: jest.fn() });
        expect(app.updateHeader).toHaveBeenCalled();
    });

    test('navigates to list', async () => {
        setupRegisterForm({ username: 'bob', password: 'pass', confirmPassword: 'pass' });
        const app = makeApp({
            api: jest.fn().mockResolvedValue({ username: 'bob', pending: false })
        });
        await app.register({ preventDefault: jest.fn() });
        expect(app.navigate).toHaveBeenCalledWith('list');
    });

    test('clears form fields after successful registration', async () => {
        setupRegisterForm({ username: 'bob', password: 'pass', confirmPassword: 'pass' });
        const app = makeApp({
            api: jest.fn().mockResolvedValue({ username: 'bob', pending: false })
        });
        await app.register({ preventDefault: jest.fn() });
        expect(document.getElementById('registerUsername').value).toBe('');
        expect(document.getElementById('registerPassword').value).toBe('');
        expect(document.getElementById('registerConfirmPassword').value).toBe('');
    });

    test('does not alert on success', async () => {
        setupRegisterForm({ username: 'bob', password: 'pass', confirmPassword: 'pass' });
        const app = makeApp({
            api: jest.fn().mockResolvedValue({ username: 'bob', pending: false })
        });
        await app.register({ preventDefault: jest.fn() });
        expect(window.alert).not.toHaveBeenCalled();
    });
});

describe('register(e) — API call', () => {
    test('calls api with /auth/register POST and correct body', async () => {
        setupRegisterForm({ username: 'alice', password: 'mypassword', confirmPassword: 'mypassword' });
        const mockApi = jest.fn().mockResolvedValue({ username: 'alice', pending: false });
        const app = makeApp({ api: mockApi });
        await app.register({ preventDefault: jest.fn() });
        expect(mockApi).toHaveBeenCalledWith('/auth/register', {
            method: 'POST',
            body: JSON.stringify({ username: 'alice', password: 'mypassword' })
        });
    });
});
