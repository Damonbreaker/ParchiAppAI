import { useEffect, useRef } from 'react';
import { useT } from '../i18n/useT.js';

// In-app confirm (never window.confirm). Closes on backdrop tap or Esc,
// except while `busy`.
export default function ConfirmSheet({ open, title, message, confirmLabel, danger, busy, error, onConfirm, onCancel }) {
  const { t } = useT();
  const cancelRef = useRef(null);

  useEffect(() => {
    if (!open) return;
    cancelRef.current?.focus();
    const onKey = (e) => {
      if (e.key === 'Escape' && !busy) onCancel();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open, busy, onCancel]);

  if (!open) return null;

  return (
    <div className="sheet-backdrop" onClick={() => !busy && onCancel()}>
      <div
        className="sheet"
        role="dialog"
        aria-modal="true"
        aria-labelledby="sheet-title"
        onClick={(e) => e.stopPropagation()}
      >
        <h2 id="sheet-title">{title}</h2>
        {message && <p className="muted">{message}</p>}
        {error && (
          <p className="form-error" role="alert">
            {error}
          </p>
        )}
        <button
          type="button"
          className={danger ? 'btn btn-danger-solid btn-block' : 'btn btn-block'}
          onClick={onConfirm}
          disabled={busy}
          aria-busy={busy}
        >
          {busy && <span className="spinner" aria-hidden="true" />}
          {busy ? t('common.wait') : confirmLabel}
        </button>
        <button ref={cancelRef} type="button" className="btn btn-secondary btn-block" onClick={onCancel} disabled={busy}>
          {t('common.goBack')}
        </button>
      </div>
    </div>
  );
}
