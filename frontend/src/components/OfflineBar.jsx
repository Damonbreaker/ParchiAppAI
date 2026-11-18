import { useEffect, useState } from 'react';
import { useT } from '../i18n/useT.js';

// Thin "No internet" bar from the browser's online/offline events.
export default function OfflineBar() {
  const { t } = useT();
  const [offline, setOffline] = useState(() => typeof navigator !== 'undefined' && navigator.onLine === false);

  useEffect(() => {
    const on = () => setOffline(false);
    const off = () => setOffline(true);
    window.addEventListener('online', on);
    window.addEventListener('offline', off);
    return () => {
      window.removeEventListener('online', on);
      window.removeEventListener('offline', off);
    };
  }, []);

  if (!offline) return null;
  return (
    <div className="offline-bar" role="status">
      {t('offline.bar')}
    </div>
  );
}
