import { state } from './state.js';
import { api } from './api.js';
// Note: renderExpertClarificationStep and submitExpertClarifications are imported
// from expertReview.js. ES modules handle this circular dependency via live bindings.
import { renderExpertClarificationStep, submitExpertClarifications } from './expertReview.js';

export function showClarificationWizard(questions) {
    const c = state.clarification;
    c.questions = questions;
    c.answers = questions.map(() => '');
    c.currentIndex = 0;
    c.active = true;

    document.getElementById('clarificationWizard').style.display = '';
    document.getElementById('replyBox').style.display = 'none';
    renderClarificationStep();
}

export function hideClarificationWizard() {
    state.clarification.active = false;
    document.getElementById('clarificationWizard').style.display = 'none';
}

export function renderClarificationStep() {
    const c = state.clarification;
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
}

export function saveClarificationAnswer() {
    const c = state.clarification;
    c.answers[c.currentIndex] = document.getElementById('clarificationAnswer').value.trim();
}

export function nextClarification() {
    if (state.expertClarification.active) {
        const c = state.expertClarification;
        c.answers[c.currentIndex] = document.getElementById('clarificationAnswer').value.trim();
        if (c.currentIndex < c.questions.length - 1) {
            c.currentIndex++;
            renderExpertClarificationStep();
        }
        return;
    }
    saveClarificationAnswer();
    const c = state.clarification;
    if (c.currentIndex < c.questions.length - 1) {
        c.currentIndex++;
        renderClarificationStep();
    }
}

export function prevClarification() {
    if (state.expertClarification.active) {
        const c = state.expertClarification;
        c.answers[c.currentIndex] = document.getElementById('clarificationAnswer').value.trim();
        if (c.currentIndex > 0) {
            c.currentIndex--;
            renderExpertClarificationStep();
        }
        return;
    }
    saveClarificationAnswer();
    const c = state.clarification;
    if (c.currentIndex > 0) {
        c.currentIndex--;
        renderClarificationStep();
    }
}

export async function submitClarifications() {
    if (state.expertClarification.active) {
        return submitExpertClarifications();
    }
    saveClarificationAnswer();
    const c = state.clarification;

    // Validate all answers are filled
    const unanswered = c.answers.findIndex(a => !a);
    if (unanswered >= 0) {
        c.currentIndex = unanswered;
        renderClarificationStep();
        document.getElementById('clarificationAnswer').focus();
        alert('Please answer question ' + (unanswered + 1) + ' before submitting.');
        return;
    }

    const answers = c.questions.map((q, i) => ({
        question: q,
        answer: c.answers[i]
    }));

    hideClarificationWizard();

    const id = state.currentSuggestion;
    await api('/suggestions/' + id + '/clarifications', {
        method: 'POST',
        body: JSON.stringify({
            answers,
            senderName: state.username || undefined
        })
    });
}

export async function loadClarificationQuestions(id) {
    try {
        const data = await api('/suggestions/' + id + '/clarification-questions');
        if (data.hasPending && data.questions && data.questions.length > 0) {
            showClarificationWizard(data.questions);
        }
    } catch (e) {
        console.error('Failed to load clarification questions:', e);
    }
}
