import { state } from './state.js';
import { api } from './api.js';
import { esc, timeAgo } from './utils.js';

export function updateNewSuggestionBtn() {
    const btn = document.getElementById('newSuggestionBtn');
    if (!btn) return;
    const allowAnon = state.settings.allowAnonymousSuggestions;
    const hasPermission = state.permissions.includes('CREATE_SUGGESTIONS');
    btn.style.display = (allowAnon || hasPermission) ? '' : 'none';
}

export function onSearchInput() {
    clearTimeout(state.searchDebounceTimer);
    state.searchDebounceTimer = setTimeout(() => applyFilters(), 300);
}

export function applyFilters() {
    const search = document.getElementById('searchInput')?.value ?? '';
    const status = document.getElementById('statusFilter')?.value ?? '';
    const priority = document.getElementById('priorityFilter')?.value ?? '';
    const sortBy = document.getElementById('sortByFilter')?.value ?? 'created';
    const sortDir = document.getElementById('sortDirFilter')?.value ?? 'desc';

    state.listFilters = { search, status, priority, sortBy, sortDir };

    const params = new URLSearchParams();
    if (search) params.set('search', search);
    if (status) params.set('status', status);
    if (priority) params.set('priority', priority);
    if (sortBy !== 'created') params.set('sortBy', sortBy);
    if (sortDir !== 'desc') params.set('sortDir', sortDir);
    const qs = params.toString();
    history.replaceState(null, '', qs ? '?' + qs : window.location.pathname);

    loadSuggestions();
}

export function restoreFiltersFromUrl() {
    const params = new URLSearchParams(window.location.search);
    const search = params.get('search') || '';
    const status = params.get('status') || '';
    const priority = params.get('priority') || '';
    const sortBy = params.get('sortBy') || 'created';
    const sortDir = params.get('sortDir') || 'desc';
    state.listFilters = { search, status, priority, sortBy, sortDir };

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
}

export function renderSuggestionItem(s, settings, queueStatus) {
    const canQuickApprove = state.permissions.includes('APPROVE_DENY_SUGGESTIONS');
    const queueMap = {};
    (queueStatus.queued || []).forEach(q => { queueMap[q.id] = q.position; });

    const showApproveActions = canQuickApprove && ['PLANNED', 'DISCUSSING'].includes(s.status);
    const priorityLabel = s.priority || 'MEDIUM';
    const queuePos = queueMap[s.id];
    const queueInfo = (s.status === 'APPROVED' && queuePos) ?
        `<div style="font-size:0.8rem;color:var(--text-muted);margin-top:0.5rem">&#9201; Queue position: ${queuePos} of ${queueStatus.queuedCount} (${queueStatus.activeCount}/${queueStatus.maxConcurrent} slots in use)</div>` : '';
    return `
        <div class="card suggestion-item" data-suggestion-id="${s.id}" onclick="app.navigate('detail', ${s.id})">
            <div class="suggestion-header">
                <div>
                    <div class="suggestion-title">${esc(s.title)}</div>
                    <div class="suggestion-meta">
                        <span>by ${esc(s.authorName || 'Anonymous')}</span>
                        <span>${timeAgo(s.createdAt)}</span>
                        ${settings.allowVoting ? `<span>&#9650; ${s.upVotes} &#9660; ${s.downVotes}</span>` : ''}
                    </div>
                </div>
                <div style="display:flex;align-items:center;gap:0.4rem;flex-wrap:wrap">
                    <span class="priority-badge priority-${priorityLabel}">${priorityLabel}</span>
                    <span class="status-badge status-${s.status}">${s.status.replace('_', ' ')}</span>
                </div>
            </div>
            ${s.currentPhase ? `<div style="font-size:0.8rem;color:${['IN_PROGRESS','EXPERT_REVIEW'].includes(s.status) ? 'var(--primary)' : 'var(--text-muted)'};margin-top:0.5rem">${['IN_PROGRESS','EXPERT_REVIEW'].includes(s.status) ? '<span class="spinner" style="display:inline-block;width:12px;height:12px;margin-right:4px;vertical-align:middle"></span>' : ''}${esc(s.currentPhase)}</div>` : ''}
            ${queueInfo}
            ${showApproveActions ? `<div class="suggestion-quick-actions" onclick="event.stopPropagation()">
                <button class="btn btn-success btn-sm" onclick="app.approveSuggestion(${s.id})">Approve</button>
                <button class="btn btn-danger btn-sm" onclick="app.denySuggestion(${s.id})">Deny</button>
            </div>` : ''}
        </div>`;
}

export async function loadSuggestions() {
    // Reset My Drafts button to default state
    const myDraftsBtn = document.getElementById('myDraftsBtn');
    if (myDraftsBtn) {
        myDraftsBtn.textContent = 'My Drafts';
        myDraftsBtn.onclick = () => app.showMyDrafts();
    }
    restoreFiltersFromUrl();
    const list = document.getElementById('suggestionList');
    list.innerHTML = '<div class="loading">Loading...</div>';

    const { search, status, priority, sortBy, sortDir } = state.listFilters;
    const params = new URLSearchParams();
    if (search) params.set('search', search);
    if (status) params.set('status', status);
    if (priority) params.set('priority', priority);
    if (sortBy && sortBy !== 'created') params.set('sortBy', sortBy);
    if (sortDir && sortDir !== 'desc') params.set('sortDir', sortDir);
    const qs = params.toString();

    try {
        const [suggestions, settings, queueStatus] = await Promise.all([
            api('/suggestions' + (qs ? '?' + qs : '')),
            api('/settings'),
            api('/suggestions/execution-queue')
        ]);
        state.settings = settings;
        state.executionQueue = queueStatus;
        updateNewSuggestionBtn();

        if (suggestions.length === 0) {
            list.innerHTML = '<div class="card" style="text-align:center;color:var(--text-muted)">No suggestions yet. Be the first to suggest a change!</div>';
            return;
        }

        list.innerHTML = suggestions.map(s => renderSuggestionItem(s, settings, queueStatus)).join('');
    } catch (err) {
        list.innerHTML = '<div class="card" style="color:var(--danger)">Failed to load suggestions.</div>';
    }
}
