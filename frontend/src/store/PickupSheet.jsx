import { useEffect, useRef, useState } from 'react';
import { markPickedUp } from '../api/endpoints.js';
import { errorText } from '../components/ErrorBox.jsx';
import WaitNote from '../components/WaitNote.jsx';
import { useRateLimit } from '../lib/useRateLimit.js';
import { useT } from '../i18n/useT.js';

const LEN = 4;

// Store enters the customer's 4-digit pickup code. One real input (paste and
// the number keypad just work) drawn as 4 big boxes. Wrong code keeps it open.
export default function PickupSheet({ requestId, onDone, onClose, initialCode = '' }) {
  const { t } = useT();
  const [code, setCode] = useState(() => String(initialCode || '').replace(/\D/g, '').slice(0, LEN));
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const inputRef = useRef(null);
  // After too many wrong codes the server locks this order for a while.
  const limit = useRateLimit(`pickup.${requestId}`);

  useEffect(() => {
    if (initialCode) setCode(String(initialCode).replace(/\D/g, '').slice(0, LEN));
  }, [initialCode]);

  useEffect(() => {
    inputRef.current?.focus();
    const onKey = (e) => {
      if (e.key === 'Escape' && !busyRef.current) onClose();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose]);

  async function submit(e) {
    e.preventDefault();
    if (busyRef.current || limit.locked) return;
    if (code.length !== LEN) {
      setError(t('pickup.err.length'));
      inputRef.current?.focus();
      return;
    }
    busyRef.current = true;
    setBusy(true);
    setError(null);
    try {
      const updated = await markPickedUp(requestId, code);
      onDone(updated);
    } catch (err) {
      busyRef.current = false;
      setBusy(false);
      if (limit.check(err)) {
        setError(null);
        setCode('');
        return;
      }
      if (err.code === 'WRONG_PICKUP_CODE') {
        setError(t('pickup.err.wrong'));
        setCode('');
      } else {
        setError(errorText(err, t));
      }
      inputRef.current?.focus();
    }
  }

  return (
    <div className="sheet-backdrop" onClick={() => !busyRef.current && onClose()}>
      <form
        className="sheet"
        role="dialog"
        aria-modal="true"
        aria-labelledby="pickup-title"
        onClick={(e) => e.stopPropagation()}
        onSubmit={submit}
        noValidate
      >
        <h2 id="pickup-title">{t('pickup.title')}</h2>
        <p className="muted">{t('pickup.hint')}</p>

        <label className="code-boxes" onClick={() => inputRef.current?.focus()}>
          <span className="visually-hidden">{t('pickup.label')}</span>
          <input
            ref={inputRef}
            className="code-input"
            inputMode="numeric"
            autoComplete="one-time-code"
            pattern="[0-9]*"
            maxLength={LEN}
            aria-invalid={error ? true : undefined}
            aria-describedby={error ? 'pickup-error' : undefined}
            value={code}
            readOnly={busy /* not disabled: a disabled input can't take focus back after a wrong code */}
            disabled={limit.locked}
            onChange={(e) => {
              setCode(e.target.value.replace(/\D/g, '').slice(0, LEN));
              setError(null);
            }}
          />
          {Array.from({ length: LEN }, (_, i) => (
            <span
              key={i}
              aria-hidden="true"
              className={`code-box${i === Math.min(code.length, LEN - 1) ? ' is-active' : ''}${error ? ' is-error' : ''}`}
            >
              {code[i] || ''}
            </span>
          ))}
        </label>

        <WaitNote secondsLeft={limit.secondsLeft} />
        {error && (
          <p id="pickup-error" className="form-error" role="alert">
            {error}
          </p>
        )}

        <button type="submit" className="btn btn-block btn-tall" disabled={busy || limit.locked} aria-busy={busy}>
          {busy && <span className="spinner" aria-hidden="true" />}
          {busy ? t('common.wait') : t('pickup.confirm')}
        </button>
        <button type="button" className="btn btn-secondary btn-block" onClick={onClose} disabled={busy}>
          {t('common.goBack')}
        </button>
      </form>
    </div>
  );
}
