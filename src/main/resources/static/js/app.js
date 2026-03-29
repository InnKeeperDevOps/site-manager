const app = {
    state: {
        loggedIn: false,
        username: '',
        role: '',
        setupRequired: false,
        permissions: [],
        currentSuggestion: null,
        currentStatus: null,
        settings: {},
        ws: null,
        notificationWs: null,
        notificationWsReconnectTimeout: null,
        recommendations: [],
        clarification: {
            questions: [],
            answers: [],
            currentIndex: 0,
            active: false
        },
        tasks: [],
        taskTimer: null,
        expertReview: {
            currentStep: -1,
            totalSteps: 0,
            experts: [],
            active: false,
            notes: []
        },
        expertClarification: {
            questions: [],
            answers: [],
            currentIndex: 0,
            active: false,
            expertName: ''
        },
        approvalPendingCount: 0,
        myDraftsMode: false,
        listFilters: {
            search: '',
            status: '',
            priority: '',
            sortBy: 'created',
            sortDir: 'desc'
        },
        searchDebounceTimer: null
    },

    async init() {
        await this.checkAuth();
    },

    // --- API helpers ---
    async api(path, opts = {}) {
        const res = await fetch('/api' + path, {
            headers: { 'Content-Type': 'application/json', ...opts.headers },
            ...opts
        });
        if (!res.ok && res.status !== 400 && res.status !== 401 && res.status !== 403) {
            throw new Error('Request failed: ' + res.status);
        }
        return res.json();
    },

    // --- Auth ---
    async checkAuth() {
        const data = await this.api('/auth/status');
        this.state.setupRequired = data.setupRequired;
        this.state.loggedIn = data.loggedIn;
        this.state.username = data.username;
        this.state.role = data.role;
        this.state.permissions = data.permissions || [];
        this.updateHeader();

        if (data.setupRequired) {
            this.navigate('setup');
        } else {
            this.navigate('list');
            this.initProjectDefinition();
        }
    },

    updateHeader() {
        const { loggedIn, username, role } = this.state;
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
        this.updateNewSuggestionBtn();
        this.updateAiRecommendationsBtn();
        this.updateProjectDefinitionBtn();
        this.updateMyDraftsBtn();
        this.updateSaveAsDraftBtn();
    },

    updateAiRecommendationsBtn() {
        const btn = document.getElementById('aiRecommendationsBtn');
        if (!btn) return;
        const { loggedIn, role } = this.state;
        btn.style.display = (loggedIn && (role === 'ROOT_ADMIN' || role === 'ADMIN')) ? '' : 'none';
    },

    updateProjectDefinitionBtn() {
        const btn = document.getElementById('project-def-btn');
        if (!btn) return;
        const { loggedIn, role } = this.state;
        btn.style.display = (loggedIn && (role === 'ROOT_ADMIN' || role === 'ADMIN')) ? '' : 'none';
    },

    updateMyDraftsBtn() {
        const btn = document.getElementById('myDraftsBtn');
        if (!btn) return;
        btn.style.display = this.state.loggedIn ? '' : 'none';
    },

    updateSaveAsDraftBtn() {
        const btn = document.getElementById('saveAsDraftBtn');
        if (!btn) return;
        btn.style.display = this.state.loggedIn ? '' : 'none';
    },

    async initProjectDefinition() {
        const isAdmin = this.state.role === 'ROOT_ADMIN' || this.state.role === 'ADMIN';
        if (!isAdmin) return;
        try {
            const res = await fetch('/api/project-definition/state');
            if (res.status === 204) return; // no active session
            if (!res.ok) return;
            const state = await res.json();
            if (!state || !state.status) return;
            if (state.status === 'COMPLETED' || state.status === 'PR_OPEN') {
                const btn = document.getElementById('project-def-btn');
                if (btn) btn.textContent = 'View Definition';
            } else if (['ACTIVE', 'GENERATING', 'SAVING'].includes(state.status)) {
                this.showProjectDefinitionModal(state);
            }
        } catch (e) {
            // Silently ignore — feature may not be configured
        }
    },

    async openProjectDefinition() {
        const modal = document.getElementById('project-def-modal');
        if (!modal) return;
        try {
            const res = await fetch('/api/project-definition/state');
            if (res.status === 204) {
                // No session — start one
                const startRes = await fetch('/api/project-definition/start', { method: 'POST', headers: { 'Content-Type': 'application/json' } });
                if (!startRes.ok) {
                    const err = await startRes.json().catch(() => ({}));
                    if (startRes.status === 409) {
                        this.showToast('A session is already in progress.');
                        return;
                    }
                    this.showToast(err.error || 'Could not start session.');
                    return;
                }
                const state = await startRes.json();
                this.showProjectDefinitionModal(state);
            } else if (res.ok) {
                const state = await res.json();
                this.showProjectDefinitionModal(state);
            }
        } catch (e) {
            this.showToast('Could not connect to the server.');
        }
    },

    showProjectDefinitionModal(state) {
        const modal = document.getElementById('project-def-modal');
        if (!modal) return;
        modal.style.display = '';

        // Progress bar
        const bar = document.getElementById('pd-progress-bar');
        if (bar) bar.style.width = (state.progressPercent || 0) + '%';

        // Status text
        const statusEl = document.getElementById('pd-status-text');
        const spinner = document.getElementById('pd-spinner');
        const spinnerText = document.getElementById('pd-spinner-text');
        const questionArea = document.getElementById('pd-question-area');
        const completeView = document.getElementById('pd-complete-view');

        // Hide all sections first
        if (spinner) spinner.style.display = 'none';
        if (questionArea) questionArea.style.display = '';
        if (completeView) completeView.style.display = 'none';

        if (state.status === 'GENERATING') {
            if (statusEl) statusEl.textContent = 'Creating your project definition document...';
            if (spinner) spinner.style.display = '';
            if (spinnerText) spinnerText.textContent = 'Generating — this may take a moment';
            if (questionArea) questionArea.style.display = 'none';
        } else if (state.status === 'SAVING') {
            if (statusEl) statusEl.textContent = 'Saving and opening a pull request...';
            if (spinner) spinner.style.display = '';
            if (spinnerText) spinnerText.textContent = 'Saving changes and opening a pull request';
            if (questionArea) questionArea.style.display = 'none';
        } else if (state.status === 'COMPLETED') {
            if (statusEl) statusEl.textContent = 'Complete';
            if (questionArea) questionArea.style.display = 'none';
            this.renderProjectDefinitionComplete(state);
        } else if (state.status === 'PR_OPEN') {
            if (statusEl) statusEl.textContent = 'Pull request is open and waiting for review';
            if (questionArea) questionArea.style.display = 'none';
            this.renderProjectDefinitionComplete(state);
        } else if (state.status === 'FAILED') {
            if (statusEl) statusEl.textContent = 'Something went wrong.';
            if (questionArea) questionArea.style.display = 'none';
            if (completeView) completeView.style.display = '';
            const errSection = document.getElementById('pd-error-section');
            if (errSection) {
                errSection.style.display = '';
                errSection.textContent = state.errorMessage || 'An error occurred. Please try again.';
            }
        } else {
            // ACTIVE — show question
            const questionEl = document.getElementById('pd-question');
            if (questionEl) questionEl.textContent = state.currentQuestion || '';

            const optionsEl = document.getElementById('pd-options');
            const textInput = document.getElementById('pd-text-input');
            const textSubmit = document.getElementById('pd-text-submit-area');

            if (state.questionType === 'MULTIPLE_CHOICE' && state.options && state.options.length > 0) {
                if (textInput) textInput.style.display = 'none';
                if (textSubmit) textSubmit.style.display = 'none';
                if (optionsEl) {
                    optionsEl.innerHTML = '';
                    state.options.forEach(opt => {
                        const btn = document.createElement('button');
                        btn.className = 'btn btn-outline';
                        btn.textContent = opt;
                        btn.style.textAlign = 'left';
                        btn.onclick = () => this.submitProjectDefinitionAnswer(opt, state.sessionId);
                        optionsEl.appendChild(btn);
                    });
                }
            } else {
                if (optionsEl) optionsEl.innerHTML = '';
                if (textInput) { textInput.style.display = ''; textInput.value = ''; }
                if (textSubmit) textSubmit.style.display = '';
                const submitBtn = document.getElementById('pd-submit-btn');
                if (submitBtn) {
                    submitBtn.disabled = false;
                    submitBtn.onclick = () => this.submitProjectDefinitionAnswer(document.getElementById('pd-text-input').value, state.sessionId);
                }
            }

            const label = state.status === 'ACTIVE' ? 'Answer the questions below to define your project.' : '';
            if (statusEl) statusEl.textContent = label;
        }

        this._pdCurrentSessionId = state.sessionId;
    },

    async submitProjectDefinitionAnswer(answer, sessionId) {
        const id = sessionId || this._pdCurrentSessionId;
        if (!id) return;
        if (!answer || !answer.trim()) {
            this.showToast('Please enter an answer before continuing.');
            return;
        }
        const submitBtn = document.getElementById('pd-submit-btn');
        if (submitBtn) submitBtn.disabled = true;
        try {
            const res = await fetch('/api/project-definition/' + id + '/answer', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ answer: answer.trim() })
            });
            if (!res.ok) {
                const err = await res.json().catch(() => ({}));
                this.showToast(err.error || 'Could not submit answer.');
                if (submitBtn) submitBtn.disabled = false;
                return;
            }
            const state = await res.json();
            this.showProjectDefinitionModal(state);
        } catch (e) {
            this.showToast('Could not connect to the server.');
            if (submitBtn) submitBtn.disabled = false;
        }
    },

    renderProjectDefinitionComplete(state) {
        const completeView = document.getElementById('pd-complete-view');
        if (!completeView) return;
        completeView.style.display = '';

        const prSection = document.getElementById('pd-pr-section');
        const prOpenSection = document.getElementById('pd-pr-open-section');
        const prLink = document.getElementById('pd-pr-link');
        const prOpenLink = document.getElementById('pd-pr-open-link');
        const contentText = document.getElementById('pd-content-text');
        const contentExpander = document.getElementById('pd-content-expander');
        const errSection = document.getElementById('pd-error-section');

        if (errSection) errSection.style.display = 'none';

        if (state.status === 'PR_OPEN') {
            if (prSection) prSection.style.display = 'none';
            if (prOpenSection) prOpenSection.style.display = '';
            if (prOpenLink && state.prUrl) prOpenLink.href = state.prUrl;
        } else if (state.prUrl) {
            if (prOpenSection) prOpenSection.style.display = 'none';
            if (prSection) prSection.style.display = '';
            if (prLink) prLink.href = state.prUrl;
        } else {
            if (prSection) prSection.style.display = 'none';
            if (prOpenSection) prOpenSection.style.display = 'none';
        }

        if (state.generatedContent && contentText) {
            const preview = state.generatedContent.substring(0, 500);
            contentText.textContent = preview;
            if (state.generatedContent.length > 500) {
                this._pdFullContent = state.generatedContent;
                if (contentExpander) contentExpander.style.display = '';
            } else {
                if (contentExpander) contentExpander.style.display = 'none';
            }
        }

        // Update button label
        const btn = document.getElementById('project-def-btn');
        if (btn) btn.textContent = 'View Definition';
    },

    expandProjectDefinitionContent(e) {
        e.preventDefault();
        const contentText = document.getElementById('pd-content-text');
        if (contentText && this._pdFullContent) {
            contentText.textContent = this._pdFullContent;
            contentText.style.maxHeight = 'none';
        }
        const expander = document.getElementById('pd-content-expander');
        if (expander) expander.style.display = 'none';
    },

    closeProjectDefinitionModal() {
        const modal = document.getElementById('project-def-modal');
        if (modal) modal.style.display = 'none';
    },

    onProjectDefinitionUpdate(wsData) {
        this.showProjectDefinitionModal(wsData);
    },

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
        toast.style.cssText = 'background:#1e293b;color:#fff;padding:0.75rem 1.25rem;border-radius:6px;box-shadow:0 4px 12px rgba(0,0,0,0.2);font-size:0.9rem;max-width:320px';
        toast.textContent = message;
        container.appendChild(toast);
        setTimeout(() => toast.remove(), 3500);
    },

    async setup(e) {
        e.preventDefault();
        const data = await this.api('/auth/setup', {
            method: 'POST',
            body: JSON.stringify({
                username: document.getElementById('setupUsername').value,
                password: document.getElementById('setupPassword').value
            })
        });
        if (data.error) { alert(data.error); return; }
        this.state.loggedIn = true;
        this.state.username = data.username;
        this.state.role = data.role;
        this.state.setupRequired = false;
        this.updateHeader();
        this.navigate('list');
    },

    async login(e) {
        e.preventDefault();
        const data = await this.api('/auth/login', {
            method: 'POST',
            body: JSON.stringify({
                username: document.getElementById('loginUsername').value,
                password: document.getElementById('loginPassword').value
            })
        });
        if (data.error) { alert(data.error); return; }
        this.state.loggedIn = true;
        this.state.username = data.username;
        this.state.role = data.role;
        this.updateHeader();
        Notification.requestPermission();
        this.connectNotificationsWs(this.state.username);
        this.navigate('list');
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

        if (data.status === 403 || (data.error && data.error.toLowerCase().includes('disabled'))) {
            alert('Registrations are currently closed. Please contact an administrator.');
            this.navigate('login');
            return;
        }
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

    async logout() {
        await this.api('/auth/logout', { method: 'POST' });
        this.state.loggedIn = false;
        this.state.username = '';
        this.state.role = '';
        this.state.notificationWs?.close();
        clearTimeout(this.state.notificationWsReconnectTimeout);
        this.updateHeader();
        this.navigate('list');
    },

    // --- Navigation ---
    navigate(view, data) {
        // Guard: block registration when it is disabled
        if (view === 'register' && this.state.settings.registrationsEnabled === false) {
            this.showToast('Registrations are currently closed.');
            this.navigate('login');
            return;
        }

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

        // Disconnect existing WebSocket
        this.disconnectWs();

        switch (view) {
            case 'list':
                this.state.myDraftsMode = false;
                this.loadSuggestions();
                break;
            case 'detail': this.loadDetail(data); break;
            case 'settings': this.loadSettings(); break;
            case 'login': {
                const regDisabled = this.state.settings.registrationsEnabled === false;
                const createLink = document.getElementById('createAccountLink');
                const closedMsg = document.getElementById('registrationsClosedMsg');
                if (createLink) createLink.style.display = regDisabled ? 'none' : '';
                if (closedMsg) closedMsg.style.display = regDisabled ? '' : 'none';
                break;
            }
            case 'create': {
                const nameGroup = document.getElementById('anonNameGroup');
                nameGroup.style.display = this.state.loggedIn ? 'none' : '';
                this.updateSaveAsDraftBtn();
                break;
            }
        }
    },

    // --- Suggestions ---
    onSearchInput() {
        clearTimeout(this.state.searchDebounceTimer);
        this.state.searchDebounceTimer = setTimeout(() => this.applyFilters(), 300);
    },

    applyFilters() {
        const search = document.getElementById('searchInput')?.value ?? '';
        const status = document.getElementById('statusFilter')?.value ?? '';
        const priority = document.getElementById('priorityFilter')?.value ?? '';
        const sortBy = document.getElementById('sortByFilter')?.value ?? 'created';
        const sortDir = document.getElementById('sortDirFilter')?.value ?? 'desc';

        this.state.listFilters = { search, status, priority, sortBy, sortDir };

        const params = new URLSearchParams();
        if (search) params.set('search', search);
        if (status) params.set('status', status);
        if (priority) params.set('priority', priority);
        if (sortBy !== 'created') params.set('sortBy', sortBy);
        if (sortDir !== 'desc') params.set('sortDir', sortDir);
        const qs = params.toString();
        history.replaceState(null, '', qs ? '?' + qs : window.location.pathname);

        this.loadSuggestions();
    },

    restoreFiltersFromUrl() {
        const params = new URLSearchParams(window.location.search);
        const search = params.get('search') || '';
        const status = params.get('status') || '';
        const priority = params.get('priority') || '';
        const sortBy = params.get('sortBy') || 'created';
        const sortDir = params.get('sortDir') || 'desc';
        this.state.listFilters = { search, status, priority, sortBy, sortDir };

        const searchEl = document.getElementById('searchInput');
        const statusEl = document.getElementById('statusFilter');
        const priorityEl = document.getElementById('priorityFilter');
        const sortByEl = document.getElementById('sortByFilter');
        const sortDirEl = document.getElementById('sortDirFilter');
        if (searchEl) searchEl.value = search;
        if (statusEl) statusEl.value = status;
        if (priorityEl) priorityEl.value = priority;
        if (sortByEl) sortByEl.value = sortBy;
        if (sortDirEl) sortDirEl.value = sortDir;
    },

    async loadSuggestions() {
        // Reset My Drafts button to default state
        const myDraftsBtn = document.getElementById('myDraftsBtn');
        if (myDraftsBtn) {
            myDraftsBtn.textContent = 'My Drafts';
            myDraftsBtn.onclick = () => this.showMyDrafts();
        }
        this.restoreFiltersFromUrl();
        const list = document.getElementById('suggestionList');
        list.innerHTML = '<div class="loading">Loading...</div>';

        const { search, status, priority, sortBy, sortDir } = this.state.listFilters;
        const params = new URLSearchParams();
        if (search) params.set('search', search);
        if (status) params.set('status', status);
        if (priority) params.set('priority', priority);
        if (sortBy && sortBy !== 'created') params.set('sortBy', sortBy);
        if (sortDir && sortDir !== 'desc') params.set('sortDir', sortDir);
        const qs = params.toString();

        try {
            const suggestions = await this.api('/suggestions' + (qs ? '?' + qs : ''));
            const settings = await this.api('/settings');
            this.state.settings = settings;
            this.updateNewSuggestionBtn();

            if (suggestions.length === 0) {
                list.innerHTML = '<div class="card" style="text-align:center;color:var(--text-muted)">No suggestions yet. Be the first to suggest a change!</div>';
                return;
            }

            const canQuickApprove = this.state.permissions.includes('APPROVE_DENY_SUGGESTIONS');

            list.innerHTML = suggestions.map(s => {
                const showApproveActions = canQuickApprove && ['PLANNED', 'DISCUSSING'].includes(s.status);
                const priorityLabel = s.priority || 'MEDIUM';
                return `
                <div class="card suggestion-item" data-suggestion-id="${s.id}" onclick="app.navigate('detail', ${s.id})">
                    <div class="suggestion-header">
                        <div>
                            <div class="suggestion-title">${this.esc(s.title)}</div>
                            <div class="suggestion-meta">
                                <span>by ${this.esc(s.authorName || 'Anonymous')}</span>
                                <span>${this.timeAgo(s.createdAt)}</span>
                                ${settings.allowVoting ? `<span>&#9650; ${s.upVotes} &#9660; ${s.downVotes}</span>` : ''}
                            </div>
                        </div>
                        <div style="display:flex;align-items:center;gap:0.4rem;flex-wrap:wrap">
                            <span class="priority-badge priority-${priorityLabel}">${priorityLabel}</span>
                            <span class="status-badge status-${s.status}">${s.status.replace('_', ' ')}</span>
                        </div>
                    </div>
                    ${s.currentPhase ? `<div style="font-size:0.8rem;color:${['IN_PROGRESS','EXPERT_REVIEW'].includes(s.status) ? 'var(--primary)' : 'var(--text-muted)'};margin-top:0.5rem">${['IN_PROGRESS','EXPERT_REVIEW'].includes(s.status) ? '<span class="spinner" style="display:inline-block;width:12px;height:12px;margin-right:4px;vertical-align:middle"></span>' : ''}${this.esc(s.currentPhase)}</div>` : ''}
                    ${showApproveActions ? `<div class="suggestion-quick-actions" onclick="event.stopPropagation()">
                        <button class="btn btn-success btn-sm" onclick="app.approveSuggestion(${s.id})">Approve</button>
                        <button class="btn btn-danger btn-sm" onclick="app.denySuggestion(${s.id})">Deny</button>
                    </div>` : ''}
                </div>`;
            }).join('');
        } catch (err) {
            list.innerHTML = '<div class="card" style="color:var(--danger)">Failed to load suggestions.</div>';
        }
    },

    async loadDetail(id) {
        this.state.currentSuggestion = id;
        const suggestion = await this.api('/suggestions/' + id);
        const messages = await this.api('/suggestions/' + id + '/messages');
        const tasks = await this.api('/suggestions/' + id + '/tasks');
        this.state.tasks = tasks || [];

        document.getElementById('detailTitle').textContent = suggestion.title;
        document.getElementById('detailDescription').textContent = suggestion.description;
        document.getElementById('detailMeta').innerHTML =
            `<span>by ${this.esc(suggestion.authorName || 'Anonymous')}</span>` +
            `<span>${this.timeAgo(suggestion.createdAt)}</span>`;

        this.state.currentStatus = suggestion.status;

        const statusEl = document.getElementById('detailStatus');
        statusEl.textContent = suggestion.status.replace('_', ' ');
        statusEl.className = 'status-badge status-' + suggestion.status;

        const priorityLabel = suggestion.priority || 'MEDIUM';
        const priorityBadge = document.getElementById('detailPriorityBadge');
        if (priorityBadge) {
            priorityBadge.textContent = priorityLabel;
            priorityBadge.className = 'priority-badge priority-' + priorityLabel;
        }

        const isAdmin = this.state.role === 'ROOT_ADMIN' || this.state.role === 'ADMIN';
        const priorityAdminEl = document.getElementById('detailPriorityAdmin');
        const prioritySelectEl = document.getElementById('detailPrioritySelect');
        if (priorityAdminEl && prioritySelectEl) {
            priorityAdminEl.style.display = isAdmin ? '' : 'none';
            prioritySelectEl.value = priorityLabel;
        }

        document.getElementById('detailUpVotes').textContent = suggestion.upVotes;
        document.getElementById('detailDownVotes').textContent = suggestion.downVotes;
        document.getElementById('detailVoteSection').style.display =
            this.state.settings.allowVoting ? '' : 'none';
        if (this.state.settings.allowVoting) {
            const isAdminForVote = this.state.role === 'ROOT_ADMIN' || this.state.role === 'ADMIN';
            const canVote = isAdminForVote || this.state.permissions.includes('VOTE');
            document.getElementById('voteUpBtn').style.display = canVote ? '' : 'none';
            document.getElementById('voteDownBtn').style.display = canVote ? '' : 'none';
        }

        const phaseEl = document.getElementById('detailPhase');
        const phaseText = document.getElementById('detailPhaseText');
        const phaseFinished = ['DENIED', 'TIMED_OUT', 'MERGED'].includes(suggestion.status) ||
            (suggestion.status === 'DEV_COMPLETE' && (!suggestion.currentPhase || suggestion.currentPhase.startsWith('Implementation completed')));
        if (suggestion.currentPhase && !phaseFinished) {
            phaseEl.style.display = '';
            phaseText.textContent = suggestion.currentPhase;
        } else {
            phaseEl.style.display = 'none';
        }

        const planEl = document.getElementById('detailPlan');
        const planText = document.getElementById('detailPlanText');
        if (suggestion.planDisplaySummary || suggestion.planSummary) {
            planEl.style.display = '';
            planText.textContent = suggestion.planDisplaySummary || suggestion.planSummary;
        } else {
            planEl.style.display = 'none';
        }

        // Expert review status — fetch current progress if in EXPERT_REVIEW
        if (suggestion.status === 'EXPERT_REVIEW' && suggestion.expertReviewStep != null) {
            this.state.expertReview.active = true;
            this.api('/suggestions/' + id + '/expert-review-status').then(data => {
                if (data && data.experts) {
                    this.updateExpertReview(data);
                }
            });
        } else {
            this.state.expertReview.active = false;
        }
        this.renderExpertReview();

        // Expert review summary panel
        this.loadReviewSummary(id, suggestion.expertReviewNotes);

        // Tasks
        this.renderTasks();

        // PR link
        const prEl = document.getElementById('detailPr');
        const prLink = document.getElementById('detailPrLink');
        if (suggestion.prUrl) {
            prEl.style.display = '';
            prLink.href = suggestion.prUrl;
            prLink.textContent = suggestion.prUrl;
        } else {
            prEl.style.display = 'none';
        }

        // Changelog
        const changelogEl = document.getElementById('detailChangelog');
        const changelogText = document.getElementById('detailChangelogText');
        if (suggestion.changelogEntry) {
            changelogEl.style.display = '';
            changelogText.textContent = suggestion.changelogEntry;
        } else {
            changelogEl.style.display = 'none';
        }

        // Admin actions
        const canApprove = ['PLANNED', 'DISCUSSING'].includes(suggestion.status);
        document.getElementById('adminActions').style.display =
            (isAdmin && canApprove) ? '' : 'none';

        // Retry PR action
        const canRetryPr = isAdmin && suggestion.currentPhase === 'Done — review request failed';
        document.getElementById('retryPrActions').style.display = canRetryPr ? '' : 'none';

        // Reply box visibility
        const statusAllowsReply = ['DRAFT', 'DISCUSSING', 'PLANNED'].includes(suggestion.status);
        const hasReplyPermission = isAdmin || this.state.permissions.includes('REPLY');
        const canReply = statusAllowsReply && hasReplyPermission;
        document.getElementById('replyBox').style.display = canReply ? '' : 'none';
        const noReplyMsg = document.getElementById('noReplyMsg');
        if (noReplyMsg) {
            noReplyMsg.style.display = (statusAllowsReply && !hasReplyPermission) ? '' : 'none';
        }

        // Render messages
        this.renderMessages(messages);

        // Check for pending clarification questions
        if (suggestion.status === 'DISCUSSING' && suggestion.pendingClarificationQuestions) {
            try {
                const questions = JSON.parse(suggestion.pendingClarificationQuestions);
                if (questions && questions.length > 0) {
                    this.showClarificationWizard(questions);
                }
            } catch (e) {
                // Fallback: load from API
                this.loadClarificationQuestions(id);
            }
        } else {
            this.hideClarificationWizard();
        }

        // Connect WebSocket
        this.connectWs(id);
    },

    renderMessages(messages) {
        const container = document.getElementById('threadContainer');
        container.innerHTML = messages.map(m => this.renderMessage(m)).join('');
        container.scrollTop = container.scrollHeight;
    },

    renderMessage(m) {
        return `
            <div class="message message-${m.senderType}">
                <div class="message-header">
                    <strong>${this.esc(m.senderName || m.senderType)}</strong>
                    <span>${this.timeAgo(m.createdAt)}</span>
                </div>
                <div class="message-content">${this.formatContent(m.content)}</div>
            </div>
        `;
    },

    renderTasks() {
        const container = document.getElementById('detailTasks');
        const listEl = document.getElementById('taskList');
        const tasks = this.state.tasks;
        if (!tasks || tasks.length === 0) {
            container.style.display = 'none';
            return;
        }
        container.style.display = '';

        const completed = tasks.filter(t => t.status === 'COMPLETED').length;
        const total = tasks.length;
        const pct = Math.round((completed / total) * 100);
        document.getElementById('taskProgress').textContent = `${completed}/${total} completed`;
        document.getElementById('taskProgressFill').style.width = pct + '%';

        // Start/stop elapsed time timer for in-progress tasks
        const hasActiveTask = tasks.some(t => (t.status === 'IN_PROGRESS' || t.status === 'REVIEWING') && t.startedAt);
        if (hasActiveTask && !this.state.taskTimer) {
            this.state.taskTimer = setInterval(() => this.renderTasks(), 10000);
        } else if (!hasActiveTask && this.state.taskTimer) {
            clearInterval(this.state.taskTimer);
            this.state.taskTimer = null;
        }

        listEl.innerHTML = tasks.map(t => {
            const icons = { PENDING: '○', IN_PROGRESS: '◉', REVIEWING: '⟳', COMPLETED: '✓', FAILED: '✗' };
            const icon = icons[t.status] || '○';
            const statusClass = t.status.toLowerCase();
            const titleClass = t.status === 'COMPLETED' ? 'task-title completed' : 'task-title';
            const statusLabel = t.status === 'REVIEWING' ? ' — reviewing' : (t.status === 'IN_PROGRESS' ? ' — in progress' : '');

            let meta = '';
            if (t.estimatedMinutes) meta += `~${t.estimatedMinutes} min`;
            if (t.startedAt && !t.completedAt) {
                const elapsed = Math.round((Date.now() - new Date(t.startedAt).getTime()) / 60000);
                meta += (meta ? ' · ' : '') + `${elapsed} min elapsed`;
            }
            if (t.startedAt && t.completedAt) {
                const dur = Math.round((new Date(t.completedAt).getTime() - new Date(t.startedAt).getTime()) / 60000);
                meta += (meta ? ' · ' : '') + `took ${dur} min`;
            }

            return `<div class="task-item" data-task-order="${t.taskOrder}">
                <div class="task-icon ${statusClass}">${icon}</div>
                <div class="task-body">
                    <div class="${titleClass}">${t.taskOrder}. ${this.esc(t.displayTitle || t.title)}${statusLabel ? `<span style="font-weight:400;font-size:0.8rem;color:${t.status === 'REVIEWING' ? '#d97706' : '#2563eb'}">${statusLabel}</span>` : ''}</div>
                    ${(t.displayDescription || t.description) ? `<div class="task-desc">${this.esc(t.displayDescription || t.description)}</div>` : ''}
                    ${meta ? `<div class="task-meta">${meta}</div>` : ''}
                </div>
            </div>`;
        }).join('');
    },

    updateTask(taskData) {
        const tasks = this.state.tasks;
        const idx = tasks.findIndex(t => t.taskOrder === taskData.taskOrder);
        const prevStatus = idx >= 0 ? tasks[idx].status : null;
        if (idx >= 0) {
            tasks[idx] = { ...tasks[idx], ...taskData };
        } else {
            tasks.push(taskData);
            tasks.sort((a, b) => a.taskOrder - b.taskOrder);
        }
        this.renderTasks();

        // Animate the changed task if status transitioned
        if (taskData.status && taskData.status !== prevStatus) {
            const taskEl = document.querySelector(`.task-item[data-task-order="${taskData.taskOrder}"]`);
            if (taskEl) {
                let animClass = '';
                if (taskData.status === 'COMPLETED') animClass = 'just-completed';
                else if (taskData.status === 'FAILED') animClass = 'just-failed';
                else if (taskData.status === 'IN_PROGRESS') animClass = 'just-started';
                if (animClass) {
                    taskEl.classList.add(animClass);
                    setTimeout(() => taskEl.classList.remove(animClass), 1500);
                }
                // Scroll the task into view
                taskEl.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
            }
        }
    },

    // --- Expert Review UI ---
    renderExpertReview() {
        const container = document.getElementById('detailExpertReview');
        const listEl = document.getElementById('expertList');
        const er = this.state.expertReview;

        if (!er.active || er.experts.length === 0) {
            container.style.display = 'none';
            return;
        }
        container.style.display = '';

        const completed = er.experts.filter(e => e.status === 'completed').length;
        const roundLabel = er.round > 1 ? ` (round ${er.round})` : '';
        document.getElementById('expertProgress').textContent =
            `${completed}/${er.totalSteps} reviewed${roundLabel}`;

        const notes = er.notes || [];
        listEl.innerHTML = er.experts.map((e, i) => {
            const icons = { pending: '○', in_progress: '◉', completed: '✓' };
            const icon = icons[e.status] || '○';
            const statusClass = 'expert-' + e.status;
            const label = e.status === 'in_progress' ? ' — reviewing' : '';
            const expertNote = notes.find(n => n.expertName === e.name);
            const noteSnippet = expertNote ? expertNote.note.substring(0, 120) + (expertNote.note.length > 120 ? '...' : '') : '';
            return `<div class="expert-item ${statusClass}">
                <div class="expert-icon">${icon}</div>
                <div class="expert-name">${this.esc(e.name)}${label ? `<span style="font-weight:400;font-size:0.8rem;color:#7c3aed">${label}</span>` : ''}${noteSnippet ? `<div style="font-weight:400;font-size:0.78rem;color:#6b7280;margin-top:2px;">${this.esc(noteSnippet)}</div>` : ''}</div>
            </div>`;
        }).join('');

        this.renderExpertNotes();
    },

    updateExpertReview(data) {
        const prevNotes = this.state.expertReview.notes || [];
        // Reset notes when a new round starts
        const isNewRound = data.round && data.round !== this.state.expertReview.round && data.currentStep === 0;
        this.state.expertReview = {
            currentStep: data.currentStep,
            totalSteps: data.totalSteps,
            experts: data.experts,
            round: data.round || 1,
            active: true,
            notes: isNewRound ? [] : prevNotes
        };
        this.renderExpertReview();
    },

    addExpertNote(expertName, note) {
        if (!this.state.expertReview.notes) {
            this.state.expertReview.notes = [];
        }
        this.state.expertReview.notes.push({ expertName, note });
        this.renderExpertNotes();
    },

    renderExpertNotes() {
        let notesEl = document.getElementById('expertNotes');
        if (!notesEl) {
            const container = document.getElementById('detailExpertReview');
            if (!container) return;
            notesEl = document.createElement('div');
            notesEl.id = 'expertNotes';
            notesEl.style.cssText = 'margin-top:0.75rem;max-height:200px;overflow-y:auto;font-size:0.85rem;';
            container.appendChild(notesEl);
        }
        const notes = this.state.expertReview.notes || [];
        if (notes.length === 0) {
            notesEl.style.display = 'none';
            return;
        }
        notesEl.style.display = '';
        notesEl.innerHTML = notes.map(n =>
            `<div style="margin-bottom:0.5rem;padding:0.4rem 0.6rem;background:#f8f7ff;border-radius:6px;border-left:3px solid #7c3aed;">
                <strong style="color:#7c3aed;font-size:0.8rem;">${this.esc(n.expertName)}</strong>
                <div style="color:#374151;margin-top:2px;">${this.esc(n.note).substring(0, 300)}${n.note.length > 300 ? '...' : ''}</div>
            </div>`
        ).join('');
    },

    // --- Expert Review Summary Panel ---
    async loadReviewSummary(id, expertReviewNotes) {
        const panel = document.getElementById('reviewSummaryPanel');
        if (!panel) return;
        // Only show if there are any notes stored
        if (!expertReviewNotes) {
            panel.style.display = 'none';
            return;
        }
        try {
            const summary = await this.api('/suggestions/' + id + '/review-summary');
            if (!Array.isArray(summary) || summary.length === 0) {
                panel.style.display = 'none';
                return;
            }
            this.renderReviewSummary(summary);
        } catch (e) {
            panel.style.display = 'none';
        }
    },

    renderReviewSummary(summary) {
        const panel = document.getElementById('reviewSummaryPanel');
        const grid = document.getElementById('reviewSummaryGrid');
        if (!panel || !grid) return;

        const dotColor = { APPROVED: '#16a34a', FLAGGED: '#d97706', PENDING: '#94a3b8' };
        const dotTitle = { APPROVED: 'Approved', FLAGGED: 'Concerns raised', PENDING: 'Not yet reviewed' };

        grid.innerHTML = summary.map(e => `
            <div style="display:flex;align-items:flex-start;gap:0.5rem;padding:0.4rem 0.6rem;background:#fff;border-radius:6px;border:1px solid #e2e8f0">
                <span title="${dotTitle[e.status] || e.status}"
                      style="flex-shrink:0;width:10px;height:10px;border-radius:50%;background:${dotColor[e.status] || '#94a3b8'};margin-top:4px"></span>
                <div style="min-width:0">
                    <div style="font-size:0.82rem;font-weight:600;color:#1e293b">${this.esc(e.expertName)}</div>
                    <div style="font-size:0.78rem;color:#64748b;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:180px"
                         title="${this.esc(e.keyPoint)}">${this.esc(e.keyPoint)}</div>
                </div>
            </div>`).join('');

        panel.style.display = '';
    },

    toggleReviewSummary() {
        const content = document.getElementById('reviewSummaryContent');
        const toggle = document.getElementById('reviewSummaryToggle');
        if (!content || !toggle) return;
        if (content.style.display === 'none') {
            content.style.display = '';
            toggle.innerHTML = '&#9660; Hide';
        } else {
            content.style.display = 'none';
            toggle.innerHTML = '&#9654; Show';
        }
    },

    showFullReviews(e) {
        e.preventDefault();
        const fullReviews = document.getElementById('detailExpertReview');
        if (fullReviews) {
            fullReviews.style.display = '';
            fullReviews.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
        }
        const link = document.getElementById('viewFullReviewsLink');
        if (link) link.style.display = 'none';
    },

    // --- Expert Clarification Wizard ---
    showExpertClarificationWizard(questions, expertName) {
        if (this.state.currentStatus && this.state.currentStatus !== 'EXPERT_REVIEW') {
            return;
        }
        const c = this.state.expertClarification;
        c.questions = questions;
        c.answers = questions.map(() => '');
        c.currentIndex = 0;
        c.active = true;
        c.expertName = expertName;

        // Reuse the same clarification wizard UI
        document.getElementById('clarificationWizard').style.display = '';
        document.getElementById('replyBox').style.display = 'none';

        // Update header to show expert name
        const headerEl = document.getElementById('clarificationWizard').querySelector('.clarification-header h4');
        if (headerEl) headerEl.textContent = expertName + ' Needs Your Input';

        this.renderExpertClarificationStep();
    },

    renderExpertClarificationStep() {
        const c = this.state.expertClarification;
        const total = c.questions.length;
        const idx = c.currentIndex;

        document.getElementById('clarificationStep').textContent = 'Question ' + (idx + 1);
        document.getElementById('clarificationTotal').textContent = 'of ' + total;
        document.getElementById('clarificationQuestionText').textContent = c.questions[idx];
        document.getElementById('clarificationAnswer').value = c.answers[idx] || '';
        document.getElementById('clarificationAnswer').focus();

        const pct = ((idx + 1) / total) * 100;
        document.getElementById('clarificationProgressFill').style.width = pct + '%';

        document.getElementById('clarificationPrevBtn').style.display = idx > 0 ? '' : 'none';
        const isLast = idx === total - 1;
        document.getElementById('clarificationNextBtn').style.display = isLast ? 'none' : '';
        document.getElementById('clarificationSubmitBtn').style.display = isLast ? '' : 'none';
    },

    async submitExpertClarifications() {
        if (this.state.currentStatus && this.state.currentStatus !== 'EXPERT_REVIEW') {
            this.hideClarificationWizard();
            return;
        }
        const c = this.state.expertClarification;
        c.answers[c.currentIndex] = document.getElementById('clarificationAnswer').value.trim();

        const unanswered = c.answers.findIndex(a => !a);
        if (unanswered >= 0) {
            c.currentIndex = unanswered;
            this.renderExpertClarificationStep();
            alert('Please answer question ' + (unanswered + 1) + ' before submitting.');
            return;
        }

        const answers = c.questions.map((q, i) => ({
            question: q,
            answer: c.answers[i]
        }));

        this.hideClarificationWizard();
        c.active = false;

        // Restore header text
        const headerEl = document.getElementById('clarificationWizard').querySelector('.clarification-header h4');
        if (headerEl) headerEl.textContent = 'Clarification Needed';

        const id = this.state.currentSuggestion;
        await this.api('/suggestions/' + id + '/expert-clarifications', {
            method: 'POST',
            body: JSON.stringify({
                answers,
                senderName: this.state.username || undefined
            })
        });
    },

    async reply() {
        const input = document.getElementById('replyInput');
        const content = input.value.trim();
        if (!content) return;

        input.value = '';
        const id = this.state.currentSuggestion;
        await this.api('/suggestions/' + id + '/messages', {
            method: 'POST',
            body: JSON.stringify({ content, senderName: this.state.username || undefined })
        });
    },

    // --- Clarification Wizard ---
    showClarificationWizard(questions) {
        const c = this.state.clarification;
        c.questions = questions;
        c.answers = questions.map(() => '');
        c.currentIndex = 0;
        c.active = true;

        document.getElementById('clarificationWizard').style.display = '';
        document.getElementById('replyBox').style.display = 'none';
        this.renderClarificationStep();
    },

    hideClarificationWizard() {
        this.state.clarification.active = false;
        document.getElementById('clarificationWizard').style.display = 'none';
    },

    renderClarificationStep() {
        const c = this.state.clarification;
        const total = c.questions.length;
        const idx = c.currentIndex;

        document.getElementById('clarificationStep').textContent = 'Question ' + (idx + 1);
        document.getElementById('clarificationTotal').textContent = 'of ' + total;
        document.getElementById('clarificationQuestionText').textContent = c.questions[idx];
        document.getElementById('clarificationAnswer').value = c.answers[idx] || '';
        document.getElementById('clarificationAnswer').focus();

        // Progress bar
        const pct = ((idx + 1) / total) * 100;
        document.getElementById('clarificationProgressFill').style.width = pct + '%';

        // Button visibility
        document.getElementById('clarificationPrevBtn').style.display = idx > 0 ? '' : 'none';
        const isLast = idx === total - 1;
        document.getElementById('clarificationNextBtn').style.display = isLast ? 'none' : '';
        document.getElementById('clarificationSubmitBtn').style.display = isLast ? '' : 'none';
    },

    saveClarificationAnswer() {
        const c = this.state.clarification;
        c.answers[c.currentIndex] = document.getElementById('clarificationAnswer').value.trim();
    },

    nextClarification() {
        if (this.state.expertClarification.active) {
            const c = this.state.expertClarification;
            c.answers[c.currentIndex] = document.getElementById('clarificationAnswer').value.trim();
            if (c.currentIndex < c.questions.length - 1) {
                c.currentIndex++;
                this.renderExpertClarificationStep();
            }
            return;
        }
        this.saveClarificationAnswer();
        const c = this.state.clarification;
        if (c.currentIndex < c.questions.length - 1) {
            c.currentIndex++;
            this.renderClarificationStep();
        }
    },

    prevClarification() {
        if (this.state.expertClarification.active) {
            const c = this.state.expertClarification;
            c.answers[c.currentIndex] = document.getElementById('clarificationAnswer').value.trim();
            if (c.currentIndex > 0) {
                c.currentIndex--;
                this.renderExpertClarificationStep();
            }
            return;
        }
        this.saveClarificationAnswer();
        const c = this.state.clarification;
        if (c.currentIndex > 0) {
            c.currentIndex--;
            this.renderClarificationStep();
        }
    },

    async submitClarifications() {
        if (this.state.expertClarification.active) {
            return this.submitExpertClarifications();
        }
        this.saveClarificationAnswer();
        const c = this.state.clarification;

        // Validate all answers are filled
        const unanswered = c.answers.findIndex(a => !a);
        if (unanswered >= 0) {
            c.currentIndex = unanswered;
            this.renderClarificationStep();
            document.getElementById('clarificationAnswer').focus();
            alert('Please answer question ' + (unanswered + 1) + ' before submitting.');
            return;
        }

        const answers = c.questions.map((q, i) => ({
            question: q,
            answer: c.answers[i]
        }));

        this.hideClarificationWizard();

        const id = this.state.currentSuggestion;
        await this.api('/suggestions/' + id + '/clarifications', {
            method: 'POST',
            body: JSON.stringify({
                answers,
                senderName: this.state.username || undefined
            })
        });
    },

    async loadClarificationQuestions(id) {
        try {
            const data = await this.api('/suggestions/' + id + '/clarification-questions');
            if (data.hasPending && data.questions && data.questions.length > 0) {
                this.showClarificationWizard(data.questions);
            }
        } catch (e) {
            console.error('Failed to load clarification questions:', e);
        }
    },

    async approve() {
        if (!confirm('Approve this suggestion and begin implementation?')) return;
        await this.api('/suggestions/' + this.state.currentSuggestion + '/approve', { method: 'POST' });
    },

    async deny() {
        const reason = prompt('Reason for denial (optional):');
        await this.api('/suggestions/' + this.state.currentSuggestion + '/deny', {
            method: 'POST',
            body: JSON.stringify({ reason })
        });
    },

    async changePriority(newPriority) {
        const id = this.state.currentSuggestion;
        if (!id) return;
        const data = await this.api('/suggestions/' + id + '/priority', {
            method: 'PATCH',
            body: JSON.stringify({ priority: newPriority })
        });
        if (data && data.error) {
            this.showToast(data.error);
            return;
        }
        const priorityLabel = data.priority || newPriority;
        const badge = document.getElementById('detailPriorityBadge');
        if (badge) {
            badge.textContent = priorityLabel;
            badge.className = 'priority-badge priority-' + priorityLabel;
        }
    },

    async approveSuggestion(id) {
        if (!confirm('Approve this suggestion and begin implementation?')) return;
        const data = await this.api('/suggestions/' + id + '/approve', { method: 'POST' });
        if (data && data.error) { this.showToast(data.error); return; }
        if (this.state.approvalPendingCount > 0) {
            this.state.approvalPendingCount--;
            this.updateApprovalBanner();
        }
        await this.loadSuggestions();
    },

    async denySuggestion(id) {
        const card = document.querySelector(`.suggestion-item[data-suggestion-id="${id}"]`);
        if (!card) return;
        const existing = card.querySelector('.deny-inline-form');
        if (existing) { existing.remove(); return; }
        const form = document.createElement('div');
        form.className = 'deny-inline-form';
        form.onclick = e => e.stopPropagation();
        form.innerHTML = `
            <textarea class="deny-reason-input" placeholder="Reason for denial (optional)" rows="2"
                style="width:100%;margin-top:0.5rem;padding:0.4rem;border:1px solid var(--border);border-radius:4px;resize:vertical;font-size:0.85rem;box-sizing:border-box"></textarea>
            <div style="margin-top:0.4rem;display:flex;gap:0.5rem">
                <button class="btn btn-danger btn-sm" onclick="app.submitDenySuggestion(${id})">Confirm Deny</button>
                <button class="btn btn-outline btn-sm" onclick="this.closest('.deny-inline-form').remove()">Cancel</button>
            </div>`;
        card.appendChild(form);
        form.querySelector('.deny-reason-input').focus();
    },

    async submitDenySuggestion(id) {
        const card = document.querySelector(`.suggestion-item[data-suggestion-id="${id}"]`);
        const reason = card ? (card.querySelector('.deny-reason-input').value || null) : null;
        const data = await this.api('/suggestions/' + id + '/deny', {
            method: 'POST',
            body: JSON.stringify({ reason })
        });
        if (data && data.error) { this.showToast(data.error); return; }
        if (this.state.approvalPendingCount > 0) {
            this.state.approvalPendingCount--;
            this.updateApprovalBanner();
        }
        await this.loadSuggestions();
    },

    async retryPr() {
        const btn = document.querySelector('#retryPrActions button');
        btn.disabled = true;
        btn.textContent = 'Retrying...';
        try {
            const result = await this.api('/suggestions/' + this.state.currentSuggestion + '/retry-pr', { method: 'POST' });
            if (result && !result.success) {
                alert('Retry failed: ' + (result.error || 'Unknown error'));
            }
        } catch (e) {
            alert('Retry failed: ' + e.message);
        } finally {
            btn.disabled = false;
            btn.textContent = 'Retry Review Request';
        }
    },

    async vote(value) {
        const id = this.state.currentSuggestion;
        const data = await this.api('/suggestions/' + id + '/vote', {
            method: 'POST',
            body: JSON.stringify({ value })
        });
        if (data.upVotes !== undefined) {
            document.getElementById('detailUpVotes').textContent = data.upVotes;
            document.getElementById('detailDownVotes').textContent = data.downVotes;
        }
        if (data.error) alert(data.error);
    },

    async createSuggestion(e) {
        e.preventDefault();
        const data = await this.api('/suggestions', {
            method: 'POST',
            body: JSON.stringify({
                title: document.getElementById('createTitle').value,
                description: document.getElementById('createDescription').value,
                authorName: document.getElementById('createAuthorName').value || undefined,
                priority: document.getElementById('createPriority').value || 'MEDIUM'
            })
        });
        if (data.error) {
            this.showToast(data.error);
            return;
        }
        document.getElementById('createForm').reset();
        this.navigate('detail', data.id);
    },

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

    async loadMyDrafts() {
        const list = document.getElementById('suggestionList');
        list.innerHTML = '<div class="loading">Loading drafts...</div>';
        try {
            const drafts = await this.api('/suggestions/my-drafts');
            if (!Array.isArray(drafts) || drafts.length === 0) {
                list.innerHTML = '<div class="card" style="text-align:center;color:var(--text-muted)">No saved drafts. Use "Save as Draft" when creating a suggestion.</div>';
                return;
            }
            list.innerHTML = this.renderDraftCards(drafts);
        } catch (err) {
            list.innerHTML = '<div class="card" style="color:var(--danger)">Failed to load drafts.</div>';
        }
    },

    renderDraftCards(drafts) {
        return drafts.map(s => {
            const priorityLabel = s.priority || 'MEDIUM';
            return `
            <div class="card suggestion-item" data-suggestion-id="${s.id}">
                <div class="suggestion-header">
                    <div>
                        <div class="suggestion-title">${this.esc(s.title)}</div>
                        <div class="suggestion-meta">
                            <span>by ${this.esc(s.authorName || 'Anonymous')}</span>
                            <span>${this.timeAgo(s.createdAt)}</span>
                        </div>
                    </div>
                    <div style="display:flex;align-items:center;gap:0.4rem;flex-wrap:wrap">
                        <span class="priority-badge priority-${priorityLabel}">${priorityLabel}</span>
                        <span class="status-badge" style="background:#f1f5f9;color:#64748b;border:1px solid #cbd5e1">DRAFT</span>
                    </div>
                </div>
                <div class="suggestion-quick-actions" onclick="event.stopPropagation()" style="margin-top:0.75rem">
                    <button class="btn btn-outline btn-sm" onclick="app.openEditDraftModal(${s.id})">Edit</button>
                    <button class="btn btn-primary btn-sm" onclick="app.submitDraftConfirm(${s.id})">Submit</button>
                </div>
            </div>`;
        }).join('');
    },

    openEditDraftModal(id) {
        const modal = document.getElementById('editDraftModal');
        if (!modal) return;
        // Fetch current draft data
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

    // --- AI Recommendations ---
    async fetchRecommendations() {
        const modal = document.getElementById('recommendationsModal');
        const content = document.getElementById('recommendationsContent');
        modal.style.display = '';
        content.innerHTML = '<div class="loading" style="padding:2rem;text-align:center">Getting suggestions from AI...</div>';

        try {
            const res = await fetch('/api/recommendations', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' }
            });

            if (res.status === 504) {
                content.innerHTML = this.renderRecommendationsError(
                    'The AI took too long to respond. Please try again in a moment.'
                );
                return;
            }

            const data = await res.json();

            if (!res.ok) {
                content.innerHTML = this.renderRecommendationsError(
                    (data && data.error) ? data.error : 'Unable to get recommendations right now.'
                );
                return;
            }

            if (!Array.isArray(data) || data.length === 0) {
                content.innerHTML = this.renderRecommendationsError(
                    'The AI returned an unexpected response. Please try again.'
                );
                return;
            }

            this.state.recommendations = data;
            content.innerHTML = data.map((rec, i) => `
                <div class="card" style="margin-bottom:0.75rem">
                    <div style="font-weight:600;margin-bottom:0.25rem">${this.esc(rec.title)}</div>
                    <div style="font-size:0.9rem;color:var(--text-muted);margin-bottom:0.75rem">${this.esc(rec.description)}</div>
                    <button class="btn btn-outline btn-sm" onclick="app.prefillFromRecommendation(${i})">Create Suggestion</button>
                </div>
            `).join('');
        } catch (err) {
            content.innerHTML = this.renderRecommendationsError(
                'Unable to connect. Please check your connection and try again.'
            );
        }
    },

    renderRecommendationsError(message) {
        return `<div class="card" style="background:#fef2f2;border-color:#fecaca;color:var(--danger)">
            <strong>Could not load recommendations</strong>
            <p style="margin-top:0.5rem;font-size:0.9rem;color:var(--danger)">${this.esc(message)}</p>
        </div>`;
    },

    closeRecommendationsModal() {
        document.getElementById('recommendationsModal').style.display = 'none';
        this.state.recommendations = [];
    },

    prefillFromRecommendation(index) {
        const rec = this.state.recommendations && this.state.recommendations[index];
        if (!rec) return;
        this.closeRecommendationsModal();
        this.navigate('create');
        document.getElementById('createTitle').value = rec.title;
        document.getElementById('createDescription').value = rec.description;
    },

    // --- WebSocket ---
    connectWs(suggestionId) {
        this.disconnectWs();
        const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
        const url = protocol + '//' + location.host + '/ws/suggestions/' + suggestionId;
        const ws = new WebSocket(url);

        ws.onmessage = (event) => {
            try {
                const data = JSON.parse(event.data);
                this.handleWsMessage(data);
            } catch (e) {
                console.error('WS parse error:', e);
            }
        };

        ws.onclose = () => {
            // Reconnect after 3 seconds if still on the same suggestion
            if (this.state.currentSuggestion === suggestionId) {
                setTimeout(() => {
                    if (this.state.currentSuggestion === suggestionId) {
                        this.connectWs(suggestionId);
                    }
                }, 3000);
            }
        };

        this.state.ws = ws;
    },

    disconnectWs() {
        if (this.state.taskTimer) {
            clearInterval(this.state.taskTimer);
            this.state.taskTimer = null;
        }
        if (this.state.ws) {
            this.state.ws.onclose = null;
            this.state.ws.close();
            this.state.ws = null;
        }
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
            if (this.state.currentSuggestion === data.suggestionId) {
                return;
            }
            const n = new Notification('Clarification Needed', {
                body: 'Your suggestion "' + data.suggestionTitle + '" needs ' + data.questionCount + ' question(s) answered.',
                tag: 'suggestion-clarification-' + data.suggestionId
            });
            n.onclick = () => { window.focus(); app.loadDetail(data.suggestionId); n.close(); };
        } else if (data.type === 'approval_needed') {
            this.state.approvalPendingCount++;
            this.updateApprovalBanner();
        } else if (data.type === 'PROJECT_DEFINITION_UPDATE') {
            this.onProjectDefinitionUpdate(data.data || data);
        }
    },

    updateApprovalBanner() {
        const isAdmin = this.state.role === 'ROOT_ADMIN' || this.state.role === 'ADMIN';
        const banner = document.getElementById('approvalPendingBanner');
        if (!banner) return;
        if (!isAdmin || this.state.approvalPendingCount === 0) {
            banner.style.display = 'none';
            return;
        }
        const count = this.state.approvalPendingCount;
        const label = count === 1 ? '1 suggestion is waiting for your approval' : count + ' suggestions are waiting for your approval';
        banner.querySelector('.approval-banner-text').textContent = label;
        banner.style.display = '';
    },

    handleWsMessage(data) {
        switch (data.type) {
            case 'message': {
                const container = document.getElementById('threadContainer');
                const div = document.createElement('div');
                div.innerHTML = this.renderMessage(data);
                container.appendChild(div.firstElementChild);
                container.scrollTop = container.scrollHeight;
                break;
            }
            case 'status_update': {
                this.state.currentStatus = data.status;

                const statusEl = document.getElementById('detailStatus');
                statusEl.textContent = data.status.replace('_', ' ');
                statusEl.className = 'status-badge status-' + data.status;

                const phaseEl = document.getElementById('detailPhase');
                const phaseText = document.getElementById('detailPhaseText');
                const phaseFinished = ['DENIED', 'TIMED_OUT', 'MERGED'].includes(data.status) ||
                    (data.status === 'DEV_COMPLETE' && (!data.currentPhase || data.currentPhase.startsWith('Implementation completed')));
                if (data.currentPhase && !phaseFinished) {
                    phaseEl.style.display = '';
                    phaseText.textContent = data.currentPhase;
                } else {
                    phaseEl.style.display = 'none';
                }

                document.getElementById('detailUpVotes').textContent = data.upVotes;
                document.getElementById('detailDownVotes').textContent = data.downVotes;

                // Update plan display if changed
                const planEl2 = document.getElementById('detailPlan');
                const planText2 = document.getElementById('detailPlanText');
                const displayPlan = data.planDisplaySummary || data.planSummary;
                if (displayPlan) {
                    planEl2.style.display = '';
                    planText2.textContent = displayPlan;
                }

                // Update admin actions visibility
                const isAdmin = this.state.role === 'ROOT_ADMIN' || this.state.role === 'ADMIN';
                const canApprove = ['PLANNED', 'DISCUSSING'].includes(data.status);
                document.getElementById('adminActions').style.display =
                    (isAdmin && canApprove) ? '' : 'none';

                // Retry PR action
                const canRetryPr2 = isAdmin && data.currentPhase === 'Done — review request failed';
                document.getElementById('retryPrActions').style.display = canRetryPr2 ? '' : 'none';

                const wsStatusAllowsReply = ['DRAFT', 'DISCUSSING', 'PLANNED'].includes(data.status);
                const wsHasReplyPermission = isAdmin || this.state.permissions.includes('REPLY');
                const canReply = wsStatusAllowsReply && wsHasReplyPermission;
                const wsNoReplyMsg = document.getElementById('noReplyMsg');
                // Only show reply box if not in clarification wizard mode
                if (!this.state.clarification.active) {
                    document.getElementById('replyBox').style.display = canReply ? '' : 'none';
                    if (wsNoReplyMsg) {
                        wsNoReplyMsg.style.display = (wsStatusAllowsReply && !wsHasReplyPermission) ? '' : 'none';
                    }
                }

                // Hide clarification wizard if status moved past DISCUSSING/EXPERT_REVIEW
                if (!['DISCUSSING', 'EXPERT_REVIEW'].includes(data.status)) {
                    this.hideClarificationWizard();
                    document.getElementById('replyBox').style.display = canReply ? '' : 'none';
                    if (wsNoReplyMsg) {
                        wsNoReplyMsg.style.display = (wsStatusAllowsReply && !wsHasReplyPermission) ? '' : 'none';
                    }
                }
                break;
            }
            case 'clarification_questions': {
                if (data.questions && data.questions.length > 0) {
                    this.showClarificationWizard(data.questions);
                }
                break;
            }
            case 'pr_created': {
                const prEl = document.getElementById('detailPr');
                const prLink = document.getElementById('detailPrLink');
                if (data.prUrl) {
                    prEl.style.display = '';
                    prLink.href = data.prUrl;
                    prLink.textContent = data.prUrl;
                }
                break;
            }
            case 'task_update': {
                if (data.task) {
                    this.updateTask(data.task);
                }
                break;
            }
            case 'tasks_update': {
                if (data.tasks) {
                    this.state.tasks = data.tasks;
                    this.renderTasks();
                }
                break;
            }
            case 'expert_review_status': {
                this.updateExpertReview(data);
                break;
            }
            case 'expert_note': {
                if (data.expertName && data.note) {
                    this.addExpertNote(data.expertName, data.note);
                }
                break;
            }
            case 'expert_clarification_questions': {
                if (data.questions && data.questions.length > 0) {
                    this.showExpertClarificationWizard(data.questions, data.expertName || 'Expert');
                }
                break;
            }
            case 'progress':
            case 'execution_progress': {
                // Show progress in phase indicator
                const phaseText = document.getElementById('detailPhaseText');
                if (data.content) {
                    phaseText.textContent = data.content.substring(0, 200);
                }
                break;
            }
        }
    },

    // --- Settings ---
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
        document.getElementById('registrationsEnabled').checked = settings.registrationsEnabled ?? true;
        const slackInput = document.getElementById('settingSlackWebhookUrl');
        slackInput.value = settings.slackWebhookUrl || '';
        slackInput.placeholder = settings.slackWebhookUrl
            ? 'Currently configured — enter new URL to replace'
            : 'https://hooks.slack.com/services/...';
        await this.loadPendingUsers();
        await this.loadGroups();
        await this.loadAllUsers();
    },

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
            '<th style="text-align:left;padding:0.5rem;border-bottom:1px solid var(--border)">Name</th>' +
            '<th style="text-align:left;padding:0.5rem;border-bottom:1px solid var(--border)">Permissions</th>' +
            '<th style="text-align:left;padding:0.5rem;border-bottom:1px solid var(--border)">Actions</th>' +
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
            editBtn.onclick = () => app.editGroup(group.id);

            const deleteBtn = document.createElement('button');
            deleteBtn.className = 'btn btn-danger btn-sm';
            deleteBtn.textContent = 'Delete';
            deleteBtn.onclick = () => app.deleteGroup(group.id);

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
            canManageUsers: document.getElementById('groupCanManageUsers').checked
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

    async loadPendingUsers() {
        const container = document.getElementById('pendingUsersContainer');
        if (!container) return;

        const section = document.getElementById('allUsersSection');
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
                '<th style="text-align:left;padding:0.5rem;border-bottom:1px solid var(--border)">Username</th>' +
                '<th style="text-align:left;padding:0.5rem;border-bottom:1px solid var(--border)">Registered</th>' +
                '<th style="text-align:left;padding:0.5rem;border-bottom:1px solid var(--border)">Actions</th>' +
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
                approveBtn.onclick = () => app.approveUser(user.id);

                const denyBtn = document.createElement('button');
                denyBtn.className = 'btn btn-danger btn-sm';
                denyBtn.textContent = 'Deny';
                denyBtn.onclick = () => app.denyUser(user.id);

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
            '<th style="text-align:left;padding:0.5rem;border-bottom:1px solid var(--border)">Username</th>' +
            '<th style="text-align:left;padding:0.5rem;border-bottom:1px solid var(--border)">Role</th>' +
            '<th style="text-align:left;padding:0.5rem;border-bottom:1px solid var(--border)">Group</th>' +
            '<th style="text-align:left;padding:0.5rem;border-bottom:1px solid var(--border)">Status</th>' +
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
            roleBadge.style.cssText = 'padding:0.15rem 0.4rem;border-radius:3px;font-size:0.8em;background:var(--border);';
            tdRole.appendChild(roleBadge);

            const tdGroup = document.createElement('td');
            tdGroup.style.padding = '0.5rem';
            const select = document.createElement('select');
            select.className = 'form-control';
            select.style.cssText = 'padding:0.2rem 0.4rem;font-size:0.9em;';
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
                app.assignUserGroup(user.id, gid);
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

    async saveSettings() {
        const data = await this.api('/settings', {
            method: 'PUT',
            body: JSON.stringify({
                siteName: document.getElementById('settingSiteName').value,
                targetRepoUrl: document.getElementById('settingRepoUrl').value,
                suggestionTimeoutMinutes: parseInt(document.getElementById('settingTimeout').value) || 1440,
                githubToken: document.getElementById('settingGithubToken').value || null,
                claudeModel: document.getElementById('settingClaudeModel').value || null,
                claudeModelExpert: document.getElementById('settingClaudeModelExpert').value || null,
                claudeMaxTurnsExpert: parseInt(document.getElementById('settingClaudeMaxTurnsExpert').value) || null,
                allowAnonymousSuggestions: document.getElementById('settingAnonymous').checked,
                allowVoting: document.getElementById('settingVoting').checked,
                requireApproval: document.getElementById('settingApproval').checked,
                autoMergePr: document.getElementById('autoMergePr').checked,
                slackWebhookUrl: document.getElementById('settingSlackWebhookUrl').value || null,
                registrationsEnabled: document.getElementById('registrationsEnabled').checked
            })
        });
        if (data.error) { alert(data.error); return; }
        this.state.settings = data;
        const siteName = document.getElementById('settingSiteName').value;
        if (siteName) document.getElementById('siteName').textContent = siteName;
        alert('Settings saved!');
    },

    async createAdmin(e) {
        e.preventDefault();
        const data = await this.api('/auth/create-admin', {
            method: 'POST',
            body: JSON.stringify({
                username: document.getElementById('newAdminUsername').value,
                password: document.getElementById('newAdminPassword').value
            })
        });
        if (data.error) { alert(data.error); return; }
        alert('Admin user created: ' + data.username);
        document.getElementById('newAdminUsername').value = '';
        document.getElementById('newAdminPassword').value = '';
    },

    // --- Helpers ---
    esc(s) {
        if (!s) return '';
        const d = document.createElement('div');
        d.textContent = s;
        return d.innerHTML;
    },

    formatContent(s) {
        if (!s) return '';
        // Basic markdown: bold
        let html = this.esc(s);
        html = html.replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>');
        return html;
    },

    timeAgo(iso) {
        if (!iso) return '';
        const d = new Date(iso);
        const now = new Date();
        const diff = Math.floor((now - d) / 1000);
        if (diff < 60) return 'just now';
        if (diff < 3600) return Math.floor(diff / 60) + 'm ago';
        if (diff < 86400) return Math.floor(diff / 3600) + 'h ago';
        return Math.floor(diff / 86400) + 'd ago';
    }
};

// Initialize
document.addEventListener('DOMContentLoaded', () => app.init());
