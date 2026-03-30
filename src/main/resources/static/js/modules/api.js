import { state } from './state.js';

export async function api(path, opts = {}) {
    const res = await fetch('/api' + path, {
        headers: { 'Content-Type': 'application/json', ...opts.headers },
        ...opts
    });
    if (!res.ok && res.status !== 400 && res.status !== 401 && res.status !== 403) {
        throw new Error('Request failed: ' + res.status);
    }
    return res.json();
}
