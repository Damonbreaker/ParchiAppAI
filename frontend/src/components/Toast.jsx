import { useEffect } from 'react';
import { useT } from '../i18n/useT.js';

const SHOW_MS = 8000;

// In-app toast above the bottom nav. Tap the text to act, × to dismiss;
// it also goes away by itself. `toast` = { id, text } or null.
export default function Toast({ toast, onAction, onClose }) {
  const { t } = useT();

  useEffect(() => {
    if (!toast) return undefined;
    const timer = setTimeout(onClose, SHOW_MS);
    return () => clearTimeout(timer);
  }, [toast, onClose]);

  return (
    <div className="toast-region" role="status" aria-live="polite">
      {toast && (
        <div className="toast">
          <button type="button" className="toast-text" onClick={onAction}>
            {toast.text}
          </button>
          <button type="button" className="toast-close" aria-label={t('quote.close')} onClick={onClose}>
            ×
          </button>
        </div>
      )}
    </div>
  );
}
