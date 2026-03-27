const app = {
    state: {
        loggedIn: false,
        username: '',
        role: '',
        setupRequired: false,
        currentSuggestion: null,
        currentStatus: null,
        settings: {},
        ws: null,
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
        }
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
        this.updateHeader();

        if (data.setupRequired) {
            this.navigate('setup');
        } else {
            this.navigate('list');
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
        this.navigate('list');
    },

    async logout() {
        await this.api('/auth/logout', { method: 'POST' });
        this.state.loggedIn = false;
        this.state.username = '';
        this.state.role = '';
        this.updateHeader();
        this.navigate('list');
    },

    // --- Navigation ---
    navigate(view, data) {
        document.querySelectorAll('.view').forEach(v => v.classList.remove('active'));
        const el = document.getElementById(view + 'View');
        if (el) el.classList.add('active');

        // Disconnect existing WebSocket
        this.disconnectWs();

        switch (view) {
            case 'list': this.loadSuggestions(); break;
            case 'detail': this.loadDetail(data); break;
            case 'settings': this.loadSettings(); break;
            case 'create':
                const nameGroup = document.getElementById('anonNameGroup');
                nameGroup.style.display = this.state.loggedIn ? 'none' : '';
                break;
        }
    },

    // --- Suggestions ---
    async loadSuggestions() {
        const list = document.getElementById('suggestionList');
        list.innerHTML = '<div class="loading">Loading...</div>';

        try {
            const suggestions = await this.api('/suggestions');
            const settings = await this.api('/settings');
            this.state.settings = settings;

            if (suggestions.length === 0) {
                list.innerHTML = '<div class="card" style="text-align:center;color:var(--text-muted)">No suggestions yet. Be the first to suggest a change!</div>';
                return;
            }

            list.innerHTML = suggestions.map(s => `
                <div class="card suggestion-item" onclick="app.navigate('detail', ${s.id})">
                    <div class="suggestion-header">
                        <div>
                            <div class="suggestion-title">${this.esc(s.title)}</div>
                            <div class="suggestion-meta">
                                <span>by ${this.esc(s.authorName || 'Anonymous')}</span>
                                <span>${this.timeAgo(s.createdAt)}</span>
                                ${settings.allowVoting ? `<span>&#9650; ${s.upVotes} &#9660; ${s.downVotes}</span>` : ''}
                            </div>
                        </div>
                        <span class="status-badge status-${s.status}">${s.status.replace('_', ' ')}</span>
                    </div>
                    ${s.currentPhase ? `<div style="font-size:0.8rem;color:${['IN_PROGRESS','EXPERT_REVIEW'].includes(s.status) ? 'var(--primary)' : 'var(--text-muted)'};margin-top:0.5rem">${['IN_PROGRESS','EXPERT_REVIEW'].includes(s.status) ? '<span class="spinner" style="display:inline-block;width:12px;height:12px;margin-right:4px;vertical-align:middle"></span>' : ''}${this.esc(s.currentPhase)}</div>` : ''}
                </div>
            `).join('');
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

        document.getElementById('detailUpVotes').textContent = suggestion.upVotes;
        document.getElementById('detailDownVotes').textContent = suggestion.downVotes;
        document.getElementById('detailVoteSection').style.display =
            this.state.settings.allowVoting ? '' : 'none';

        const phaseEl = document.getElementById('detailPhase');
        const phaseText = document.getElementById('detailPhaseText');
        const phaseFinished = ['DENIED', 'TIMED_OUT'].includes(suggestion.status) ||
            (suggestion.status === 'COMPLETED' && (!suggestion.currentPhase || suggestion.currentPhase.startsWith('Implementation completed')));
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
        const isAdmin = this.state.role === 'ROOT_ADMIN' || this.state.role === 'ADMIN';
        const canApprove = ['PLANNED', 'DISCUSSING'].includes(suggestion.status);
        document.getElementById('adminActions').style.display =
            (isAdmin && canApprove) ? '' : 'none';

        // Retry PR action
        const canRetryPr = isAdmin && suggestion.currentPhase === 'Done — review request failed';
        document.getElementById('retryPrActions').style.display = canRetryPr ? '' : 'none';

        // Reply box visibility
        const canReply = ['DRAFT', 'DISCUSSING', 'PLANNED'].includes(suggestion.status);
        document.getElementById('replyBox').style.display = canReply ? '' : 'none';

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
                authorName: document.getElementById('createAuthorName').value || undefined
            })
        });
        if (data.error) { alert(data.error); return; }
        document.getElementById('createForm').reset();
        this.navigate('detail', data.id);
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
                const phaseFinished = ['DENIED', 'TIMED_OUT'].includes(data.status) ||
                    (data.status === 'COMPLETED' && (!data.currentPhase || data.currentPhase.startsWith('Implementation completed')));
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

                const canReply = ['DRAFT', 'DISCUSSING', 'PLANNED'].includes(data.status);
                // Only show reply box if not in clarification wizard mode
                if (!this.state.clarification.active) {
                    document.getElementById('replyBox').style.display = canReply ? '' : 'none';
                }

                // Hide clarification wizard if status moved past DISCUSSING/EXPERT_REVIEW
                if (!['DISCUSSING', 'EXPERT_REVIEW'].includes(data.status)) {
                    this.hideClarificationWizard();
                    document.getElementById('replyBox').style.display = canReply ? '' : 'none';
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
                requireApproval: document.getElementById('settingApproval').checked
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
