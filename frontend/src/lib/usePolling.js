import { useEffect, useRef } from 'react';

// Calls `callback` every `intervalMs` while the page is visible. Hidden tab ->
// timer stops; visible again -> calls once right away and restarts the timer.
// The first load is the caller's job. enabled=false stops polling entirely.
export function usePolling(callback, intervalMs, enabled = true) {
  const callbackRef = useRef(callback);
  useEffect(() => {
    callbackRef.current = callback;
  });

  useEffect(() => {
    if (!enabled) return undefined;
    let timer = null;
    const stop = () => {
      if (timer) clearInterval(timer);
      timer = null;
    };
    const start = () => {
      stop();
      timer = setInterval(() => callbackRef.current(), intervalMs);
    };
    const onVisibility = () => {
      if (document.visibilityState === 'visible') {
        callbackRef.current();
        start();
      } else {
        stop();
      }
    };

    if (document.visibilityState === 'visible') start();
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      stop();
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, [intervalMs, enabled]);
}
