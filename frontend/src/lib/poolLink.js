export function poolJoinUrl(token) {
  const base = typeof window !== 'undefined' ? window.location.origin : '';
  return `${base}/c/pool/${encodeURIComponent(token)}`;
}

export function formatCountdown(secondsLeft) {
  const s = Math.max(0, Number(secondsLeft) || 0);
  const m = Math.floor(s / 60);
  const r = s % 60;
  return `${m}:${String(r).padStart(2, '0')}`;
}
