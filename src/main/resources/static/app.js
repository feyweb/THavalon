'use strict';

/* Shared helpers. No framework, no build step — the whole client is this file plus four pages. */

const TOKEN_PREFIX = 'thavalon:token:';
const LAST_GAME = 'thavalon:last';

/**
 * Where a player's token lives.
 *
 * The token is what makes reconnect work: a locked phone or a reopened tab comes back to the
 * same role instead of erroring or joining twice.
 *
 * It is written to both storages on purpose:
 *
 * - `sessionStorage` is per-tab, so several players can sit in one browser without overwriting
 *   each other. Without this, every tab reads the same token and shows the same player — which
 *   is exactly what happens when you test a five-player game in five tabs.
 * - `localStorage` is shared and outlives the tab, so closing and reopening still finds you.
 *
 * Reads prefer the tab's own identity and fall back to the device's.
 */
const store = {
  token(gameId) {
    return sessionStorage.getItem(TOKEN_PREFIX + gameId)
        || localStorage.getItem(TOKEN_PREFIX + gameId);
  },
  remember(gameId, token) {
    sessionStorage.setItem(TOKEN_PREFIX + gameId, token);
    sessionStorage.setItem(LAST_GAME, gameId);
    localStorage.setItem(TOKEN_PREFIX + gameId, token);
    localStorage.setItem(LAST_GAME, gameId);
  },
  forget(gameId) {
    sessionStorage.removeItem(TOKEN_PREFIX + gameId);
    localStorage.removeItem(TOKEN_PREFIX + gameId);
    if (sessionStorage.getItem(LAST_GAME) === gameId) sessionStorage.removeItem(LAST_GAME);
    if (localStorage.getItem(LAST_GAME) === gameId) localStorage.removeItem(LAST_GAME);
  },
  lastGame() {
    const id = sessionStorage.getItem(LAST_GAME) || localStorage.getItem(LAST_GAME);
    return id && this.token(id) ? id : null;
  },
};

class ApiError extends Error {
  constructor(code, message, status) {
    super(message);
    this.code = code;
    this.status = status;
  }
}

async function api(path, { method = 'GET', body, gameId } = {}) {
  const headers = {};
  if (body) headers['Content-Type'] = 'application/json';

  const token = gameId ? store.token(gameId) : null;
  if (token) headers['X-Player-Token'] = token;

  const response = await fetch(path, {
    method,
    headers,
    body: body ? JSON.stringify(body) : undefined,
    cache: 'no-store',
  });

  if (response.status === 204) return null;

  const payload = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new ApiError(payload.code || 'ERROR', payload.message || 'Something went wrong.', response.status);
  }
  return payload;
}

function gameIdFromUrl() {
  return (new URLSearchParams(location.search).get('g') || '').toUpperCase();
}

function goTo(page, gameId) {
  location.href = `${page}?g=${encodeURIComponent(gameId)}`;
}

function showError(message) {
  const box = document.getElementById('error');
  if (!box) return;
  box.textContent = message || '';
  // The error box sits at the top of the page while the buttons that trigger it are further
  // down. Without this, a failed join on a scrolled page looks like nothing happened at all.
  if (message) {
    box.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }
}

/** Guards a button against double submits while its action is in flight. */
function onClick(id, handler) {
  const button = document.getElementById(id);
  if (!button) return;
  button.addEventListener('click', async () => {
    button.disabled = true;
    showError('');
    try {
      await handler();
    } catch (e) {
      showError(e.message);
    } finally {
      button.disabled = false;
    }
  });
}

/** Enter submits, so nobody has to reach for a button on a phone keyboard. */
function submitOnEnter(inputId, buttonId) {
  const input = document.getElementById(inputId);
  if (!input) return;
  input.addEventListener('keydown', (event) => {
    if (event.key === 'Enter') {
      event.preventDefault();
      document.getElementById(buttonId)?.click();
    }
  });
}
