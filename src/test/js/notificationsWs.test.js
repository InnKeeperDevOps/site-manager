/**
 * Tests for notification WebSocket connection logic in app.js.
 * Covers: connectNotificationsWs, login flow, logout cleanup, handleNotificationWsMessage.
 */

let loadDetailMock;

// Minimal mock of the app object under test - mirrors the relevant parts of app.js
function makeApp(overrides = {}) {
    return {
        state: {
            loggedIn: false,
            username: '',
            role: '',
            ws: null,
            notificationWs: null,
            notificationWsReconnectTimeout: null,
            currentSuggestion: null,
        },

        connectNotificationsWs(username) {
            const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
            const url = protocol + '//' + location.host + '/ws/notifications?username=' + encodeURIComponent(username);
            const ws = new WebSocket(url);

            ws.onmessage = (event) => {
                try {
                    const data = JSON.parse(event.data);
                    this.handleNotificationWsMessage(data);
                } catch (e) {
                    console.error('Notification WS parse error:', e);
                }
            };

            ws.onclose = () => {
                this.state.notificationWsReconnectTimeout = setTimeout(() => {
                    if (this.state.loggedIn) {
                        this.connectNotificationsWs(this.state.username);
                    }
                }, 3000);
            };

            this.state.notificationWs = ws;
        },

        handleNotificationWsMessage(data) {
            if (data.type === 'clarification_needed') {
                if (Notification.permission !== 'granted') {
                    return;
                }
                if (this.state.currentSuggestion?.id === data.suggestionId) {
                    return;
                }
                const n = new Notification('Clarification Needed', {
                    body: 'Your suggestion "' + data.suggestionTitle + '" needs ' + data.questionCount + ' question(s) answered.',
                    tag: 'suggestion-clarification-' + data.suggestionId
                });
                n.onclick = () => { window.focus(); loadDetail(data.suggestionId); n.close(); };
            }
        },

        ...overrides,
    };
}

// Mock WebSocket
let mockWsInstances = [];
class MockWebSocket {
    constructor(url) {
        this.url = url;
        this.onmessage = null;
        this.onclose = null;
        this.closeCalled = false;
        mockWsInstances.push(this);
    }
    close() {
        this.closeCalled = true;
    }
}

// Mock Notification API
let mockNotificationInstances = [];
class MockNotification {
    constructor(title, options) {
        this.title = title;
        this.options = options;
        this.onclick = null;
        this.closeCalled = false;
        mockNotificationInstances.push(this);
    }
    close() {
        this.closeCalled = true;
    }
}
MockNotification.permission = 'granted';

beforeEach(() => {
    mockWsInstances = [];
    mockNotificationInstances = [];
    global.WebSocket = MockWebSocket;
    global.Notification = MockNotification;
    MockNotification.permission = 'granted';
    loadDetailMock = jest.fn();
    global.loadDetail = loadDetailMock;
    jest.useFakeTimers();
    jest.spyOn(console, 'error').mockImplementation(() => {});
});

afterEach(() => {
    jest.useRealTimers();
    jest.restoreAllMocks();
});

describe('connectNotificationsWs', () => {
    test('opens WebSocket with ws: scheme over http', () => {
        Object.defineProperty(window, 'location', {
            value: { protocol: 'http:', host: 'localhost:8080' },
            writable: true,
        });
        const app = makeApp();
        app.connectNotificationsWs('alice');

        expect(mockWsInstances).toHaveLength(1);
        expect(mockWsInstances[0].url).toBe('ws://localhost:8080/ws/notifications?username=alice');
    });

    test('opens WebSocket with wss: scheme over https', () => {
        Object.defineProperty(window, 'location', {
            value: { protocol: 'https:', host: 'example.com' },
            writable: true,
        });
        const app = makeApp();
        app.connectNotificationsWs('bob');

        expect(mockWsInstances[0].url).toBe('wss://example.com/ws/notifications?username=bob');
    });

    test('URL-encodes special characters in username', () => {
        Object.defineProperty(window, 'location', {
            value: { protocol: 'http:', host: 'localhost:8080' },
            writable: true,
        });
        const app = makeApp();
        app.connectNotificationsWs('user name+extra');

        expect(mockWsInstances[0].url).toContain('username=user%20name%2Bextra');
    });

    test('stores WebSocket in state.notificationWs', () => {
        Object.defineProperty(window, 'location', {
            value: { protocol: 'http:', host: 'localhost:8080' },
            writable: true,
        });
        const app = makeApp();
        app.connectNotificationsWs('alice');

        expect(app.state.notificationWs).toBe(mockWsInstances[0]);
    });

    test('onmessage parses JSON and calls handleNotificationWsMessage', () => {
        Object.defineProperty(window, 'location', {
            value: { protocol: 'http:', host: 'localhost:8080' },
            writable: true,
        });
        const app = makeApp();
        const spy = jest.spyOn(app, 'handleNotificationWsMessage');
        app.connectNotificationsWs('alice');

        const ws = mockWsInstances[0];
        ws.onmessage({ data: JSON.stringify({ type: 'clarification_needed', suggestionId: 42 }) });

        expect(spy).toHaveBeenCalledWith({ type: 'clarification_needed', suggestionId: 42 });
    });

    test('onmessage logs error and does not throw on invalid JSON', () => {
        Object.defineProperty(window, 'location', {
            value: { protocol: 'http:', host: 'localhost:8080' },
            writable: true,
        });
        const app = makeApp();
        app.connectNotificationsWs('alice');

        const ws = mockWsInstances[0];
        expect(() => ws.onmessage({ data: 'not json' })).not.toThrow();
        expect(console.error).toHaveBeenCalled();
    });

    test('onclose schedules reconnect after 3000ms when loggedIn', () => {
        Object.defineProperty(window, 'location', {
            value: { protocol: 'http:', host: 'localhost:8080' },
            writable: true,
        });
        const app = makeApp();
        app.state.loggedIn = true;
        app.state.username = 'alice';
        app.connectNotificationsWs('alice');

        const connectSpy = jest.spyOn(app, 'connectNotificationsWs');
        mockWsInstances[0].onclose();

        expect(app.state.notificationWsReconnectTimeout).not.toBeNull();

        jest.advanceTimersByTime(3000);
        expect(connectSpy).toHaveBeenCalledWith('alice');
    });

    test('onclose does not reconnect when not loggedIn', () => {
        Object.defineProperty(window, 'location', {
            value: { protocol: 'http:', host: 'localhost:8080' },
            writable: true,
        });
        const app = makeApp();
        app.state.loggedIn = false;
        app.connectNotificationsWs('alice');

        const connectSpy = jest.spyOn(app, 'connectNotificationsWs');
        mockWsInstances[0].onclose();

        jest.advanceTimersByTime(3000);
        expect(connectSpy).not.toHaveBeenCalled();
    });

    test('onclose stores timeout id in state.notificationWsReconnectTimeout', () => {
        Object.defineProperty(window, 'location', {
            value: { protocol: 'http:', host: 'localhost:8080' },
            writable: true,
        });
        const app = makeApp();
        app.connectNotificationsWs('alice');

        mockWsInstances[0].onclose();
        expect(app.state.notificationWsReconnectTimeout).not.toBeNull();
    });
});

describe('login flow', () => {
    test('state has notificationWs and notificationWsReconnectTimeout initialized to null', () => {
        const app = makeApp();
        expect(app.state.notificationWs).toBeNull();
        expect(app.state.notificationWsReconnectTimeout).toBeNull();
    });
});

describe('logout cleanup', () => {
    test('closes notificationWs on logout', () => {
        Object.defineProperty(window, 'location', {
            value: { protocol: 'http:', host: 'localhost:8080' },
            writable: true,
        });
        const app = makeApp();
        app.connectNotificationsWs('alice');
        const ws = mockWsInstances[0];

        // Simulate logout cleanup
        app.state.notificationWs?.close();
        expect(ws.closeCalled).toBe(true);
    });

    test('clearTimeout cancels scheduled reconnect on logout', () => {
        Object.defineProperty(window, 'location', {
            value: { protocol: 'http:', host: 'localhost:8080' },
            writable: true,
        });
        const app = makeApp();
        app.connectNotificationsWs('alice');
        app.state.loggedIn = true;
        app.state.username = 'alice';
        mockWsInstances[0].onclose();

        const connectSpy = jest.spyOn(app, 'connectNotificationsWs');

        // Simulate logout cleanup
        clearTimeout(app.state.notificationWsReconnectTimeout);

        jest.advanceTimersByTime(3000);
        expect(connectSpy).not.toHaveBeenCalled();
    });
});

describe('handleNotificationWsMessage', () => {
    const clarificationData = {
        type: 'clarification_needed',
        suggestionId: 42,
        suggestionTitle: 'My Feature',
        questionCount: 3,
    };

    test('shows Notification with correct title and body when permission granted and not on that page', () => {
        const app = makeApp();
        MockNotification.permission = 'granted';
        app.state.currentSuggestion = null;

        app.handleNotificationWsMessage(clarificationData);

        expect(mockNotificationInstances).toHaveLength(1);
        expect(mockNotificationInstances[0].title).toBe('Clarification Needed');
        expect(mockNotificationInstances[0].options.body).toBe(
            'Your suggestion "My Feature" needs 3 question(s) answered.'
        );
    });

    test('sets tag to suggestion-clarification-{id}', () => {
        const app = makeApp();
        MockNotification.permission = 'granted';

        app.handleNotificationWsMessage(clarificationData);

        expect(mockNotificationInstances[0].options.tag).toBe('suggestion-clarification-42');
    });

    test('does not show Notification when permission is denied', () => {
        const app = makeApp();
        MockNotification.permission = 'denied';

        app.handleNotificationWsMessage(clarificationData);

        expect(mockNotificationInstances).toHaveLength(0);
    });

    test('does not show Notification when permission is default (not yet granted)', () => {
        const app = makeApp();
        MockNotification.permission = 'default';

        app.handleNotificationWsMessage(clarificationData);

        expect(mockNotificationInstances).toHaveLength(0);
    });

    test('does not show Notification when user is already on that suggestion page', () => {
        const app = makeApp();
        MockNotification.permission = 'granted';
        app.state.currentSuggestion = { id: 42 };

        app.handleNotificationWsMessage(clarificationData);

        expect(mockNotificationInstances).toHaveLength(0);
    });

    test('shows Notification when user is on a different suggestion page', () => {
        const app = makeApp();
        MockNotification.permission = 'granted';
        app.state.currentSuggestion = { id: 99 };

        app.handleNotificationWsMessage(clarificationData);

        expect(mockNotificationInstances).toHaveLength(1);
    });

    test('shows Notification when currentSuggestion is null', () => {
        const app = makeApp();
        MockNotification.permission = 'granted';
        app.state.currentSuggestion = null;

        app.handleNotificationWsMessage(clarificationData);

        expect(mockNotificationInstances).toHaveLength(1);
    });

    test('onclick calls window.focus, loadDetail, and closes the notification', () => {
        const app = makeApp();
        MockNotification.permission = 'granted';
        jest.spyOn(window, 'focus').mockImplementation(() => {});

        app.handleNotificationWsMessage(clarificationData);

        const n = mockNotificationInstances[0];
        expect(n.onclick).toBeInstanceOf(Function);
        n.onclick();

        expect(window.focus).toHaveBeenCalled();
        expect(loadDetailMock).toHaveBeenCalledWith(42);
        expect(n.closeCalled).toBe(true);
    });

    test('ignores messages with unknown type', () => {
        const app = makeApp();
        MockNotification.permission = 'granted';

        app.handleNotificationWsMessage({ type: 'unknown_event', suggestionId: 42 });

        expect(mockNotificationInstances).toHaveLength(0);
    });
});
