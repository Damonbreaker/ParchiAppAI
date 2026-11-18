import { useCallback, useEffect, useState } from 'react';

// "12 min" / "45 sec" for how long until the server allows another try.
export function formatWait(seconds, t) {
  return seconds >= 60 ? t('wait.min', { n: Math.ceil(seconds / 60) }) : t('wait.sec', { n: Math.max(1, seconds) });
}

export const isTooMany = (err) => err?.code === 'TOO_MANY_ATTEMPTS' || err?.status === 429;

const read = (key) => {
  try {
    return Number(sessionStorage.getItem(key)) || 0;
  } catch {
    return 0;
  }
};
const write = (key, until) => {
  try {
    if (until) sessionStorage.setItem(key, String(until));
    else sessionStorage.removeItem(key);
  } catch {
    // storage unavailable; the lock lives only in memory
  }
};

// After a 429 the action stays locked with a live countdown. key (optional) keeps the
// lock in sessionStorage, so closing and reopening a sheet doesn't hide it.
// -> { locked, secondsLeft, check(err) -> true if it was a 429 }
export function useRateLimit(key) {
  const storageKey = key ? `kirana.wait.${key}` : null;
  const [until, setUntil] = useState(() => (storageKey ? read(storageKey) : 0));
  const [now, setNow] = useState(() => Date.now());

  // A different key (e.g. another phone number typed on Login) has its own lock.
  const [prevKey, setPrevKey] = useState(storageKey);
  if (prevKey !== storageKey) {
    setPrevKey(storageKey);
    setUntil(storageKey ? read(storageKey) : 0);
    setNow(Date.now());
  }

  useEffect(() => {
    if (until <= Date.now()) return undefined;
    const timer = setInterval(() => {
      const t = Date.now();
      setNow(t);
      if (t >= until) {
        clearInterval(timer);
        if (storageKey) write(storageKey, 0);
      }
    }, 1000);
    return () => clearInterval(timer);
  }, [until, storageKey]);

  const check = useCallback(
    (err) => {
      if (!isTooMany(err)) return false;
      const next = Date.now() + (err.retryAfter || 60) * 1000;
      setNow(Date.now());
      setUntil(next);
      if (storageKey) write(storageKey, next);
      return true;
    },
    [storageKey]
  );

  const secondsLeft = Math.max(0, Math.ceil((until - now) / 1000));
  return { locked: secondsLeft > 0, secondsLeft, check };
}
