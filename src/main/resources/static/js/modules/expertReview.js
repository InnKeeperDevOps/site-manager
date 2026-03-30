import { state } from './state.js';
import { api } from './api.js';
import { esc } from './utils.js';
// Note: hideClarificationWizard is imported from clarification.js at runtime.
// ES modules handle this circular dependency via live bindings.
import { hideClarificationWizard } from './clarification.js';

export function renderExpertReview() {
    const container = document.getElementById('detailExpertReview');
    const listEl = document.getElementById('expertList');
    const er = state.expertReview;

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
    listEl.innerHTML = er.experts.map((e) => {
        const icons = { pending: '○', in_progress: '◉', completed: '✓' };
        const icon = icons[e.status] || '○';
        const statusClass = 'expert-' + e.status;
        const label = e.status === 'in_progress' ? ' — reviewing' : '';
        const expertNote = notes.find(n => n.expertName === e.name);
        const noteSnippet = expertNote ? expertNote.note.substring(0, 120) + (expertNote.note.length > 120 ? '...' : '') : '';
        return `<div class="expert-item ${statusClass}">
            <div class="expert-icon">${icon}</div>
            <div class="expert-name">${esc(e.name)}${label ? `<span style="font-weight:400;font-size:0.8rem;color:#7c3aed">${label}</span>` : ''}${noteSnippet ? `<div style="font-weight:400;font-size:0.78rem;color:#6b7280;margin-top:2px;">${esc(noteSnippet)}</div>` : ''}</div>
        </div>`;
    }).join('');

    renderExpertNotes();
}

export function updateExpertReview(data) {
    const prevNotes = state.expertReview.notes || [];
    // Reset notes when a new round starts
    const isNewRound = data.round && data.round !== state.expertReview.round && data.currentStep === 0;
    state.expertReview = {
        currentStep: data.currentStep,
        totalSteps: data.totalSteps,
        experts: data.experts,
        round: data.round || 1,
        active: true,
        notes: isNewRound ? [] : prevNotes
    };
    renderExpertReview();
}

export function addExpertNote(expertName, note) {
    if (!state.expertReview.notes) {
        state.expertReview.notes = [];
    }
    state.expertReview.notes.push({ expertName, note });
    renderExpertNotes();
}

export function renderExpertNotes() {
    let notesEl = document.getElementById('expertNotes');
    if (!notesEl) {
        const container = document.getElementById('detailExpertReview');
        if (!container) return;
        notesEl = document.createElement('div');
        notesEl.id = 'expertNotes';
        notesEl.style.cssText = 'margin-top:0.75rem;max-height:200px;overflow-y:auto;font-size:0.85rem;';
        container.appendChild(notesEl);
    }
    const notes = state.expertReview.notes || [];
    if (notes.length === 0) {
        notesEl.style.display = 'none';
        return;
    }
    notesEl.style.display = '';
    notesEl.innerHTML = notes.map(n =>
        `<div style="margin-bottom:0.5rem;padding:0.4rem 0.6rem;background:#f8f7ff;border-radius:6px;border-left:3px solid #7c3aed;">
            <strong style="color:#7c3aed;font-size:0.8rem;">${esc(n.expertName)}</strong>
            <div style="color:#374151;margin-top:2px;">${esc(n.note).substring(0, 300)}${n.note.length > 300 ? '...' : ''}</div>
        </div>`
    ).join('');
}

export async function loadReviewSummary(id, expertReviewNotes) {
    const panel = document.getElementById('reviewSummaryPanel');
    if (!panel) return;
    // Only show if there are any notes stored
    if (!expertReviewNotes) {
        panel.style.display = 'none';
        return;
    }
    try {
        const summary = await api('/suggestions/' + id + '/review-summary');
        if (!Array.isArray(summary) || summary.length === 0) {
            panel.style.display = 'none';
            return;
        }
        renderReviewSummary(summary);
    } catch (e) {
        panel.style.display = 'none';
    }
}

export function renderReviewSummary(summary) {
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
                <div style="font-size:0.82rem;font-weight:600;color:#1e293b">${esc(e.expertName)}</div>
                <div style="font-size:0.78rem;color:#64748b;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:180px"
                     title="${esc(e.keyPoint)}">${esc(e.keyPoint)}</div>
            </div>
        </div>`).join('');

    panel.style.display = '';
}

export function toggleReviewSummary() {
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
}

export function showFullReviews(e) {
    e.preventDefault();
    const fullReviews = document.getElementById('detailExpertReview');
    if (fullReviews) {
        fullReviews.style.display = '';
        fullReviews.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    }
    const link = document.getElementById('viewFullReviewsLink');
    if (link) link.style.display = 'none';
}

export function showExpertClarificationWizard(questions, expertName) {
    if (state.currentStatus && state.currentStatus !== 'EXPERT_REVIEW') {
        return;
    }
    const c = state.expertClarification;
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

    renderExpertClarificationStep();
}

export function renderExpertClarificationStep() {
    const c = state.expertClarification;
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
}

export async function submitExpertClarifications() {
    if (state.currentStatus && state.currentStatus !== 'EXPERT_REVIEW') {
        hideClarificationWizard();
        return;
    }
    const c = state.expertClarification;
    c.answers[c.currentIndex] = document.getElementById('clarificationAnswer').value.trim();

    const unanswered = c.answers.findIndex(a => !a);
    if (unanswered >= 0) {
        c.currentIndex = unanswered;
        renderExpertClarificationStep();
        alert('Please answer question ' + (unanswered + 1) + ' before submitting.');
        return;
    }

    const answers = c.questions.map((q, i) => ({
        question: q,
        answer: c.answers[i]
    }));

    hideClarificationWizard();
    c.active = false;

    // Restore header text
    const headerEl = document.getElementById('clarificationWizard').querySelector('.clarification-header h4');
    if (headerEl) headerEl.textContent = 'Clarification Needed';

    const id = state.currentSuggestion;
    await api('/suggestions/' + id + '/expert-clarifications', {
        method: 'POST',
        body: JSON.stringify({
            answers,
            senderName: state.username || undefined
        })
    });
}
