import { esc, timeAgo, showToast } from './utils.js';

export async function loadDashboardView() {
    const body = document.getElementById('leaderboardBody');
    if (body) body.innerHTML = '<tr><td colspan="7">Loading...</td></tr>';
    const panel = document.getElementById('userHistoryPanel');
    if (panel) panel.style.display = 'none';
    try {
        const res = await fetch('/api/contributors/leaderboard');
        const data = await res.json();
        renderLeaderboard(data);
    } catch (e) {
        if (body) body.innerHTML = '<tr><td colspan="7">Could not load leaderboard.</td></tr>';
    }
}

export function renderLeaderboard(contributors) {
    const body = document.getElementById('leaderboardBody');
    if (!body) return;
    const medals = ['\uD83E\uDD47', '\uD83E\uDD48', '\uD83E\uDD49'];
    body.innerHTML = '';
    contributors.forEach((c, i) => {
        const rank = i + 1;
        const rankDisplay = rank <= 3 ? medals[rank - 1] : String(rank);
        const tr = document.createElement('tr');
        tr.innerHTML =
            '<td style="padding:0.5rem">' + rankDisplay + '</td>' +
            '<td style="padding:0.5rem"><a href="#" class="contributor-link"' +
                ' data-author-id="' + esc(c.authorId) + '"' +
                ' data-username="' + esc(c.username) + '">' +
                esc(c.username) + '</a></td>' +
            '<td style="padding:0.5rem">' + c.totalSubmissions + '</td>' +
            '<td style="padding:0.5rem">' + c.mergedSuggestions + '</td>' +
            '<td style="padding:0.5rem">' + c.approvedSuggestions + '</td>' +
            '<td style="padding:0.5rem">' + c.totalUpvotesReceived + '</td>' +
            '<td style="padding:0.5rem">' + c.score + '</td>';
        body.appendChild(tr);
    });
    body.onclick = async (e) => {
        const link = e.target.closest('.contributor-link');
        if (!link) return;
        e.preventDefault();
        const authorId = link.dataset.authorId;
        const username = link.dataset.username;
        try {
            const res = await fetch('/api/contributors/' + encodeURIComponent(authorId) + '/history');
            const suggestions = await res.json();
            renderUserHistory(username, suggestions);
        } catch (err) {
            showToast('Could not load history.');
        }
    };
}

export function renderUserHistory(username, suggestions) {
    const panel = document.getElementById('userHistoryPanel');
    const nameEl = document.getElementById('historyUsername');
    const listEl = document.getElementById('historyList');
    if (!panel || !nameEl || !listEl) return;
    panel.style.display = '';
    nameEl.textContent = username + "'s Submissions";
    if (!suggestions || suggestions.length === 0) {
        listEl.innerHTML = '<p>No submissions found.</p>';
        return;
    }
    listEl.innerHTML = '';
    suggestions.forEach(s => {
        const div = document.createElement('div');
        div.style.cssText = 'padding:0.75rem;border:1px solid var(--border);border-radius:6px;margin-bottom:0.5rem';
        div.innerHTML =
            '<a href="#" onclick="app.navigate(\'detail\',{id:' + s.id + '});return false;"' +
                ' style="font-weight:500">' + esc(s.title) + '</a>' +
            '<span class="badge" style="margin-left:0.5rem">' + esc(s.status) + '</span>' +
            '<span style="color:var(--muted);font-size:0.85rem;margin-left:0.5rem">' + timeAgo(s.createdAt) + '</span>' +
            '<span style="margin-left:0.5rem">\u25B2 ' + s.upVotes + '</span>';
        listEl.appendChild(div);
    });
}
