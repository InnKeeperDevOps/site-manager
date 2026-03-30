import { state } from './state.js';
import { esc, showToast } from './utils.js';

export async function fetchRecommendations() {
    const modal = document.getElementById('recommendationsModal');
    const content = document.getElementById('recommendationsContent');
    modal.style.display = '';
    content.innerHTML = '<div class="loading" style="padding:2rem;text-align:center">Getting suggestions from AI...<br><span style="font-size:0.85rem;color:var(--text-muted)">This may take a couple of minutes</span></div>';

    try {
        const startRes = await fetch('/api/recommendations', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' }
        });

        if (!startRes.ok) {
            const err = await startRes.json().catch(() => ({}));
            content.innerHTML = renderRecommendationsError(
                (err && err.error) ? err.error : 'Unable to get recommendations right now.'
            );
            return;
        }

        const { taskId } = await startRes.json();

        const data = await pollRecommendationTask(taskId, content);
        if (!data) return;

        if (!Array.isArray(data) || data.length === 0) {
            content.innerHTML = renderRecommendationsError(
                'The AI returned an unexpected response. Please try again.'
            );
            return;
        }

        state.recommendations = data;
        content.innerHTML = data.map((rec, i) => `
            <div class="card" style="margin-bottom:0.75rem">
                <div style="font-weight:600;margin-bottom:0.25rem">${esc(rec.title)}</div>
                <div style="font-size:0.9rem;color:var(--text-muted);margin-bottom:0.75rem">${esc(rec.description)}</div>
                <button class="btn btn-outline btn-sm" onclick="app.prefillFromRecommendation(${i})">Create Suggestion</button>
            </div>
        `).join('');
    } catch (err) {
        content.innerHTML = renderRecommendationsError(
            'Unable to connect. Please check your connection and try again.'
        );
    }
}

export async function pollRecommendationTask(taskId, content) {
    const maxPolls = 120;
    for (let i = 0; i < maxPolls; i++) {
        await new Promise(r => setTimeout(r, 5000));

        const modal = document.getElementById('recommendationsModal');
        if (modal && modal.style.display === 'none') return null;

        try {
            const res = await fetch('/api/recommendations/status/' + taskId);
            if (!res.ok) {
                content.innerHTML = renderRecommendationsError(
                    'Lost track of the recommendation task. Please try again.'
                );
                return null;
            }
            const result = await res.json();
            if (result.status === 'pending') continue;
            if (result.status === 'error') {
                content.innerHTML = renderRecommendationsError(result.error);
                return null;
            }
            return result.data;
        } catch (e) {
            // Network blip — keep polling
        }
    }
    content.innerHTML = renderRecommendationsError(
        'The AI is taking unusually long. Please try again.'
    );
    return null;
}

export function renderRecommendationsError(message) {
    return `<div class="card" style="background:#fef2f2;border-color:#fecaca;color:var(--danger)">
        <strong>Could not load recommendations</strong>
        <p style="margin-top:0.5rem;font-size:0.9rem;color:var(--danger)">${esc(message)}</p>
    </div>`;
}

export function closeRecommendationsModal() {
    document.getElementById('recommendationsModal').style.display = 'none';
    state.recommendations = [];
}

export function prefillFromRecommendation(index) {
    const rec = state.recommendations && state.recommendations[index];
    if (!rec) return;
    closeRecommendationsModal();
    if (window.app && window.app.navigate) {
        window.app.navigate('create');
    }
    document.getElementById('createTitle').value = rec.title;
    document.getElementById('createDescription').value = rec.description;
}
