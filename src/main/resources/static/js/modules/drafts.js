import { state } from './state.js';
import { api } from './api.js';
import { esc, timeAgo, showToast } from './utils.js';
import { loadSuggestions } from './suggestions.js';

export function showMyDrafts() {
    state.myDraftsMode = true;
    const btn = document.getElementById('myDraftsBtn');
    if (btn) btn.textContent = 'All Suggestions';
    if (btn) btn.onclick = () => showAllSuggestions();
    loadMyDrafts();
}

export function showAllSuggestions() {
    state.myDraftsMode = false;
    const btn = document.getElementById('myDraftsBtn');
    if (btn) btn.textContent = 'My Drafts';
    if (btn) btn.onclick = () => showMyDrafts();
    loadSuggestions();
}

export async function loadMyDrafts() {
    const list = document.getElementById('suggestionList');
    list.innerHTML = '<div class="loading">Loading drafts...</div>';
    try {
        const drafts = await api('/suggestions/my-drafts');
        if (!Array.isArray(drafts) || drafts.length === 0) {
            list.innerHTML = '<div class="card" style="text-align:center;color:var(--text-muted)">No saved drafts. Use "Save as Draft" when creating a suggestion.</div>';
            return;
        }
        list.innerHTML = renderDraftCards(drafts);
    } catch (err) {
        list.innerHTML = '<div class="card" style="color:var(--danger)">Failed to load drafts.</div>';
    }
}

export function renderDraftCards(drafts) {
    return drafts.map(s => {
        const priorityLabel = s.priority || 'MEDIUM';
        return `
        <div class="card suggestion-item" data-suggestion-id="${s.id}">
            <div class="suggestion-header">
                <div>
                    <div class="suggestion-title">${esc(s.title)}</div>
                    <div class="suggestion-meta">
                        <span>by ${esc(s.authorName || 'Anonymous')}</span>
                        <span>${timeAgo(s.createdAt)}</span>
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
}

export function openEditDraftModal(id) {
    const modal = document.getElementById('editDraftModal');
    if (!modal) return;
    api('/suggestions/' + id).then(s => {
        if (s.error) { showToast(s.error); return; }
        document.getElementById('editDraftId').value = id;
        document.getElementById('editDraftTitle').value = s.title || '';
        document.getElementById('editDraftDescription').value = s.description || '';
        document.getElementById('editDraftPriority').value = s.priority || 'MEDIUM';
        modal.style.display = '';
    });
}

export function closeEditDraftModal() {
    const modal = document.getElementById('editDraftModal');
    if (modal) modal.style.display = 'none';
}

export async function saveEditDraft() {
    const id = document.getElementById('editDraftId').value;
    if (!id) return;
    const data = await api('/suggestions/' + id + '/draft', {
        method: 'PATCH',
        body: JSON.stringify({
            title: document.getElementById('editDraftTitle').value,
            description: document.getElementById('editDraftDescription').value,
            priority: document.getElementById('editDraftPriority').value
        })
    });
    if (data.error) {
        showToast(data.error);
        return;
    }
    closeEditDraftModal();
    showToast('Draft updated.');
    loadMyDrafts();
}

export async function submitDraftConfirm(id) {
    if (!confirm('Submit this draft? It will be sent for review.')) return;
    const data = await api('/suggestions/' + id + '/submit', { method: 'POST' });
    if (data.error) {
        showToast(data.error);
        return;
    }
    showToast('Suggestion submitted.');
    showAllSuggestions();
    // navigate to detail is handled by app.js via window.app
    if (window.app && window.app.navigate) {
        window.app.navigate('detail', data.id || id);
    }
}
