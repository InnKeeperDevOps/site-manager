/**
 * Tests for Task 5: permission-based vote button and reply form rendering.
 *
 * Covers:
 *   - Vote buttons shown/hidden based on VOTE permission and admin role
 *   - Vote section hidden when allowVoting=false
 *   - Reply box shown/hidden based on REPLY permission and suggestion status
 *   - noReplyMsg shown when status allows reply but user lacks REPLY permission
 *   - WebSocket status-update path applies same reply permission logic
 */

// ---------------------------------------------------------------------------
// DOM helpers
// ---------------------------------------------------------------------------

function setupDetailDOM() {
    document.body.innerHTML = `
        <div id="detailVoteSection" style="display:none">
            <div class="vote-controls">
                <button id="voteUpBtn" class="vote-btn">▲</button>
                <span class="vote-count" id="detailUpVotes">0</span>
                <button id="voteDownBtn" class="vote-btn">▼</button>
                <span class="vote-count" id="detailDownVotes">0</span>
            </div>
        </div>
        <div id="adminActions" style="display:none"></div>
        <div id="retryPrActions" style="display:none"></div>
        <div id="replyBox" style="display:none">
            <input id="replyInput" type="text">
            <button>Send</button>
        </div>
        <div id="noReplyMsg" style="display:none">Register and get approved to join the discussion.</div>
    `;
}

// ---------------------------------------------------------------------------
// Minimal app factory — only state and the logic under test
// ---------------------------------------------------------------------------

/**
 * Applies vote section visibility (mirrors the loadDetail vote block in app.js).
 */
function applyVoteRendering(app, allowVoting) {
    document.getElementById('detailVoteSection').style.display = allowVoting ? '' : 'none';
    if (allowVoting) {
        const isAdmin = app.state.role === 'ROOT_ADMIN' || app.state.role === 'ADMIN';
        const canVote = isAdmin || app.state.permissions.includes('VOTE');
        document.getElementById('voteUpBtn').style.display = canVote ? '' : 'none';
        document.getElementById('voteDownBtn').style.display = canVote ? '' : 'none';
    }
}

/**
 * Applies reply box visibility (mirrors the loadDetail reply block in app.js).
 */
function applyReplyRendering(app, status) {
    const isAdmin = app.state.role === 'ROOT_ADMIN' || app.state.role === 'ADMIN';
    const statusAllowsReply = ['DRAFT', 'DISCUSSING', 'PLANNED'].includes(status);
    const hasReplyPermission = isAdmin || app.state.permissions.includes('REPLY');
    const canReply = statusAllowsReply && hasReplyPermission;
    document.getElementById('replyBox').style.display = canReply ? '' : 'none';
    const noReplyMsg = document.getElementById('noReplyMsg');
    if (noReplyMsg) {
        noReplyMsg.style.display = (statusAllowsReply && !hasReplyPermission) ? '' : 'none';
    }
}

/**
 * Applies reply box visibility from WS update path (mirrors the WS handler in app.js).
 */
function applyWsReplyRendering(app, status, clarificationActive = false) {
    const isAdmin = app.state.role === 'ROOT_ADMIN' || app.state.role === 'ADMIN';
    const wsStatusAllowsReply = ['DRAFT', 'DISCUSSING', 'PLANNED'].includes(status);
    const wsHasReplyPermission = isAdmin || app.state.permissions.includes('REPLY');
    const canReply = wsStatusAllowsReply && wsHasReplyPermission;
    const wsNoReplyMsg = document.getElementById('noReplyMsg');
    if (!clarificationActive) {
        document.getElementById('replyBox').style.display = canReply ? '' : 'none';
        if (wsNoReplyMsg) {
            wsNoReplyMsg.style.display = (wsStatusAllowsReply && !wsHasReplyPermission) ? '' : 'none';
        }
    }
    if (!['DISCUSSING', 'EXPERT_REVIEW'].includes(status)) {
        document.getElementById('replyBox').style.display = canReply ? '' : 'none';
        if (wsNoReplyMsg) {
            wsNoReplyMsg.style.display = (wsStatusAllowsReply && !wsHasReplyPermission) ? '' : 'none';
        }
    }
}

function makeApp(overrides = {}) {
    return {
        state: {
            role: '',
            permissions: [],
            ...overrides.state,
        },
        ...overrides,
    };
}

afterEach(() => {
    document.body.innerHTML = '';
});

// ---------------------------------------------------------------------------
// Vote section — allowVoting=false
// ---------------------------------------------------------------------------

describe('Vote section — allowVoting disabled', () => {
    test('hides entire vote section when allowVoting is false', () => {
        setupDetailDOM();
        const app = makeApp({ state: { role: 'USER', permissions: ['VOTE'] } });
        applyVoteRendering(app, false);
        expect(document.getElementById('detailVoteSection').style.display).toBe('none');
    });

    test('vote buttons state is irrelevant when section is hidden', () => {
        setupDetailDOM();
        const app = makeApp({ state: { role: 'USER', permissions: ['VOTE'] } });
        applyVoteRendering(app, false);
        // Section hidden; button display not touched, so they remain whatever default was
        expect(document.getElementById('detailVoteSection').style.display).toBe('none');
    });
});

// ---------------------------------------------------------------------------
// Vote section — allowVoting=true, with VOTE permission
// ---------------------------------------------------------------------------

describe('Vote section — user has VOTE permission', () => {
    test('shows vote section', () => {
        setupDetailDOM();
        const app = makeApp({ state: { role: 'USER', permissions: ['VOTE'] } });
        applyVoteRendering(app, true);
        expect(document.getElementById('detailVoteSection').style.display).toBe('');
    });

    test('shows vote up button', () => {
        setupDetailDOM();
        const app = makeApp({ state: { role: 'USER', permissions: ['VOTE'] } });
        applyVoteRendering(app, true);
        expect(document.getElementById('voteUpBtn').style.display).toBe('');
    });

    test('shows vote down button', () => {
        setupDetailDOM();
        const app = makeApp({ state: { role: 'USER', permissions: ['VOTE'] } });
        applyVoteRendering(app, true);
        expect(document.getElementById('voteDownBtn').style.display).toBe('');
    });
});

// ---------------------------------------------------------------------------
// Vote section — allowVoting=true, without VOTE permission
// ---------------------------------------------------------------------------

describe('Vote section — user lacks VOTE permission', () => {
    test('shows vote section (counts still visible)', () => {
        setupDetailDOM();
        const app = makeApp({ state: { role: 'USER', permissions: [] } });
        applyVoteRendering(app, true);
        expect(document.getElementById('detailVoteSection').style.display).toBe('');
    });

    test('hides vote up button', () => {
        setupDetailDOM();
        const app = makeApp({ state: { role: 'USER', permissions: [] } });
        applyVoteRendering(app, true);
        expect(document.getElementById('voteUpBtn').style.display).toBe('none');
    });

    test('hides vote down button', () => {
        setupDetailDOM();
        const app = makeApp({ state: { role: 'USER', permissions: [] } });
        applyVoteRendering(app, true);
        expect(document.getElementById('voteDownBtn').style.display).toBe('none');
    });

    test('vote counts spans remain in DOM (static display)', () => {
        setupDetailDOM();
        const app = makeApp({ state: { role: 'USER', permissions: [] } });
        applyVoteRendering(app, true);
        expect(document.getElementById('detailUpVotes')).not.toBeNull();
        expect(document.getElementById('detailDownVotes')).not.toBeNull();
    });
});

// ---------------------------------------------------------------------------
// Vote section — admin roles bypass VOTE permission
// ---------------------------------------------------------------------------

describe('Vote section — admin roles always get vote buttons', () => {
    test('ROOT_ADMIN sees vote buttons even without VOTE in permissions array', () => {
        setupDetailDOM();
        const app = makeApp({ state: { role: 'ROOT_ADMIN', permissions: [] } });
        applyVoteRendering(app, true);
        expect(document.getElementById('voteUpBtn').style.display).toBe('');
        expect(document.getElementById('voteDownBtn').style.display).toBe('');
    });

    test('ADMIN sees vote buttons even without VOTE in permissions array', () => {
        setupDetailDOM();
        const app = makeApp({ state: { role: 'ADMIN', permissions: [] } });
        applyVoteRendering(app, true);
        expect(document.getElementById('voteUpBtn').style.display).toBe('');
        expect(document.getElementById('voteDownBtn').style.display).toBe('');
    });
});

// ---------------------------------------------------------------------------
// Reply box — user has REPLY permission and status allows reply
// ---------------------------------------------------------------------------

describe('Reply box — user has REPLY permission', () => {
    const replyStatuses = ['DRAFT', 'DISCUSSING', 'PLANNED'];
    const nonReplyStatuses = ['DENIED', 'MERGED', 'TIMED_OUT', 'DEV_COMPLETE'];

    replyStatuses.forEach(status => {
        test(`shows reply box for status=${status}`, () => {
            setupDetailDOM();
            const app = makeApp({ state: { role: 'USER', permissions: ['REPLY'] } });
            applyReplyRendering(app, status);
            expect(document.getElementById('replyBox').style.display).toBe('');
        });

        test(`hides noReplyMsg for status=${status} with REPLY permission`, () => {
            setupDetailDOM();
            const app = makeApp({ state: { role: 'USER', permissions: ['REPLY'] } });
            applyReplyRendering(app, status);
            expect(document.getElementById('noReplyMsg').style.display).toBe('none');
        });
    });

    nonReplyStatuses.forEach(status => {
        test(`hides reply box for terminal status=${status}`, () => {
            setupDetailDOM();
            const app = makeApp({ state: { role: 'USER', permissions: ['REPLY'] } });
            applyReplyRendering(app, status);
            expect(document.getElementById('replyBox').style.display).toBe('none');
        });
    });
});

// ---------------------------------------------------------------------------
// Reply box — user lacks REPLY permission
// ---------------------------------------------------------------------------

describe('Reply box — user lacks REPLY permission', () => {
    test('hides reply box when status allows but no REPLY permission', () => {
        setupDetailDOM();
        const app = makeApp({ state: { role: 'USER', permissions: [] } });
        applyReplyRendering(app, 'DISCUSSING');
        expect(document.getElementById('replyBox').style.display).toBe('none');
    });

    test('shows noReplyMsg when status allows but no REPLY permission', () => {
        setupDetailDOM();
        const app = makeApp({ state: { role: 'USER', permissions: [] } });
        applyReplyRendering(app, 'DISCUSSING');
        expect(document.getElementById('noReplyMsg').style.display).toBe('');
    });

    test('hides noReplyMsg when status does not allow reply (terminal)', () => {
        setupDetailDOM();
        const app = makeApp({ state: { role: 'USER', permissions: [] } });
        applyReplyRendering(app, 'MERGED');
        expect(document.getElementById('noReplyMsg').style.display).toBe('none');
    });

    test('hides reply box for DRAFT status without REPLY permission', () => {
        setupDetailDOM();
        const app = makeApp({ state: { role: 'USER', permissions: [] } });
        applyReplyRendering(app, 'DRAFT');
        expect(document.getElementById('replyBox').style.display).toBe('none');
    });

    test('shows noReplyMsg for PLANNED status without REPLY permission', () => {
        setupDetailDOM();
        const app = makeApp({ state: { role: 'USER', permissions: [] } });
        applyReplyRendering(app, 'PLANNED');
        expect(document.getElementById('noReplyMsg').style.display).toBe('');
    });
});

// ---------------------------------------------------------------------------
// Reply box — admin roles bypass REPLY permission
// ---------------------------------------------------------------------------

describe('Reply box — admin roles bypass REPLY permission check', () => {
    test('ROOT_ADMIN can reply without REPLY in permissions array', () => {
        setupDetailDOM();
        const app = makeApp({ state: { role: 'ROOT_ADMIN', permissions: [] } });
        applyReplyRendering(app, 'DISCUSSING');
        expect(document.getElementById('replyBox').style.display).toBe('');
    });

    test('ADMIN can reply without REPLY in permissions array', () => {
        setupDetailDOM();
        const app = makeApp({ state: { role: 'ADMIN', permissions: [] } });
        applyReplyRendering(app, 'DISCUSSING');
        expect(document.getElementById('replyBox').style.display).toBe('');
    });

    test('ROOT_ADMIN does not see noReplyMsg', () => {
        setupDetailDOM();
        const app = makeApp({ state: { role: 'ROOT_ADMIN', permissions: [] } });
        applyReplyRendering(app, 'DISCUSSING');
        expect(document.getElementById('noReplyMsg').style.display).toBe('none');
    });
});

// ---------------------------------------------------------------------------
// noReplyMsg element absent — graceful handling
// ---------------------------------------------------------------------------

describe('noReplyMsg absent — no throw', () => {
    test('applyReplyRendering does not throw when noReplyMsg element is missing', () => {
        document.body.innerHTML = `
            <div id="replyBox" style="display:none"></div>
        `;
        const app = makeApp({ state: { role: 'USER', permissions: [] } });
        expect(() => applyReplyRendering(app, 'DISCUSSING')).not.toThrow();
    });
});

// ---------------------------------------------------------------------------
// WebSocket path — reply box permission check
// ---------------------------------------------------------------------------

describe('WebSocket status update — reply permission applied', () => {
    test('hides reply box when WS update arrives and user lacks REPLY permission', () => {
        setupDetailDOM();
        document.getElementById('replyBox').style.display = '';
        const app = makeApp({ state: { role: 'USER', permissions: [] } });
        applyWsReplyRendering(app, 'DISCUSSING');
        expect(document.getElementById('replyBox').style.display).toBe('none');
    });

    test('shows reply box when WS update arrives and user has REPLY permission', () => {
        setupDetailDOM();
        const app = makeApp({ state: { role: 'USER', permissions: ['REPLY'] } });
        applyWsReplyRendering(app, 'DISCUSSING');
        expect(document.getElementById('replyBox').style.display).toBe('');
    });

    test('hides reply box on terminal status regardless of REPLY permission', () => {
        setupDetailDOM();
        const app = makeApp({ state: { role: 'USER', permissions: ['REPLY'] } });
        applyWsReplyRendering(app, 'MERGED');
        expect(document.getElementById('replyBox').style.display).toBe('none');
    });

    test('shows noReplyMsg when WS update has open status but user lacks REPLY permission', () => {
        setupDetailDOM();
        const app = makeApp({ state: { role: 'USER', permissions: [] } });
        applyWsReplyRendering(app, 'DISCUSSING');
        expect(document.getElementById('noReplyMsg').style.display).toBe('');
    });

    test('hides noReplyMsg when WS update status is terminal', () => {
        setupDetailDOM();
        const app = makeApp({ state: { role: 'USER', permissions: [] } });
        applyWsReplyRendering(app, 'DENIED');
        expect(document.getElementById('noReplyMsg').style.display).toBe('none');
    });

    test('does not update reply box when clarification wizard is active', () => {
        setupDetailDOM();
        document.getElementById('replyBox').style.display = '';
        const app = makeApp({ state: { role: 'USER', permissions: [] } });
        // Clarification active AND status is not outside DISCUSSING/EXPERT_REVIEW range
        applyWsReplyRendering(app, 'DISCUSSING', true);
        // Should not have changed since clarification is active and status is DISCUSSING
        expect(document.getElementById('replyBox').style.display).toBe('');
    });

    test('still hides reply when clarification active but status is terminal', () => {
        setupDetailDOM();
        document.getElementById('replyBox').style.display = '';
        const app = makeApp({ state: { role: 'USER', permissions: ['REPLY'] } });
        // Status is terminal (not DISCUSSING or EXPERT_REVIEW), so second block fires even with clarification active
        applyWsReplyRendering(app, 'MERGED', true);
        expect(document.getElementById('replyBox').style.display).toBe('none');
    });
});

// ---------------------------------------------------------------------------
// Combined VOTE + REPLY permissions
// ---------------------------------------------------------------------------

describe('Combined vote and reply permissions', () => {
    test('user with both VOTE and REPLY sees buttons and reply box', () => {
        setupDetailDOM();
        const app = makeApp({ state: { role: 'USER', permissions: ['VOTE', 'REPLY'] } });
        applyVoteRendering(app, true);
        applyReplyRendering(app, 'DISCUSSING');
        expect(document.getElementById('voteUpBtn').style.display).toBe('');
        expect(document.getElementById('voteDownBtn').style.display).toBe('');
        expect(document.getElementById('replyBox').style.display).toBe('');
        expect(document.getElementById('noReplyMsg').style.display).toBe('none');
    });

    test('user with neither permission sees counts only, no buttons, no reply box, and hint message', () => {
        setupDetailDOM();
        const app = makeApp({ state: { role: 'USER', permissions: [] } });
        applyVoteRendering(app, true);
        applyReplyRendering(app, 'DISCUSSING');
        expect(document.getElementById('voteUpBtn').style.display).toBe('none');
        expect(document.getElementById('voteDownBtn').style.display).toBe('none');
        expect(document.getElementById('detailUpVotes')).not.toBeNull();
        expect(document.getElementById('replyBox').style.display).toBe('none');
        expect(document.getElementById('noReplyMsg').style.display).toBe('');
    });
});
