const KEY = 'kirana.guestKey';
const NAME_KEY = 'kirana.guestName';

function randomKey() {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) return crypto.randomUUID();
  return `g${Date.now().toString(36)}${Math.random().toString(36).slice(2, 10)}`;
}

export function getGuestKey() {
  try {
    let key = localStorage.getItem(KEY);
    if (!key) {
      key = randomKey();
      localStorage.setItem(KEY, key);
    }
    return key;
  } catch {
    return randomKey();
  }
}

export function getGuestName() {
  try {
    return localStorage.getItem(NAME_KEY) || '';
  } catch {
    return '';
  }
}

export function setGuestName(name) {
  try {
    const n = String(name || '').trim().slice(0, 40);
    if (n) localStorage.setItem(NAME_KEY, n);
    else localStorage.removeItem(NAME_KEY);
  } catch {
    // ignore
  }
}

/** Absolute join URL for WhatsApp / copy. */
export function sharedListJoinUrl(token) {
  const base = typeof window !== 'undefined' ? window.location.origin : '';
  return `${base}/join/${encodeURIComponent(token)}`;
}
