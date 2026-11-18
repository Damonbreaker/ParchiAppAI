import { useEffect, useRef, useState } from 'react';
import QuoteLines from '../components/QuoteLines.jsx';
import { availabilityText } from '../components/QuoteCard.jsx';
import { errorText } from '../components/ErrorBox.jsx';
import { formatKm } from '../lib/geo.js';
import { formatINR } from '../lib/money.js';
import { useT } from '../i18n/useT.js';

// Bottom sheet with one quote, line by line. Closes on backdrop tap, Esc or Back.
// canAccept: request is OPEN. onAccept(quote) -> Promise; it throws ApiErrors for this sheet to show.
export default function QuoteSheet({ quote: polledQuote, canAccept, startConfirming = false, onAccept, onClose }) {
  const { t, tn } = useT();
  const closeRef = useRef(null);
  const busyRef = useRef(false);
  const [confirming, setConfirming] = useState(Boolean(startConfirming));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  // 409 QUOTE_CHANGED returns the store's current quote; show it until polling catches up.
  const [changed, setChanged] = useState(null);
  const quote = changed && changed.quoteId === polledQuote.quoteId ? changed : polledQuote;

  useEffect(() => {
    if (changed && Number(polledQuote.totalPrice) === Number(changed.totalPrice)) setChanged(null);
  }, [polledQuote.totalPrice, changed]);

  useEffect(() => {
    closeRef.current?.focus();
    const onKey = (e) => {
      if (e.key === 'Escape' && !busyRef.current) onClose();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose]);

  async function accept() {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    setError(null);
    try {
      await onAccept(quote);
      // The parent swaps the page to the order view and closes this sheet.
    } catch (err) {
      if (err.code === 'QUOTE_CHANGED' && err.body?.quote) {
        setChanged(err.body.quote);
        setError(t('accept.changed', { price: formatINR(err.body.quote.totalPrice) }));
      } else {
        setError(errorText(err, t));
      }
      busyRef.current = false;
      setBusy(false);
    }
  }

  const where = (
    <>
      {quote.area}
      {quote.distanceKm != null && ` · ${t('unit.km', { km: formatKm(quote.distanceKm) })}`}
    </>
  );

  return (
    <div className="sheet-backdrop" onClick={() => !busyRef.current && onClose()}>
      <div
        className="sheet sheet-tall"
        role="dialog"
        aria-modal="true"
        aria-labelledby="quote-sheet-title"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="section-head">
          <h2 id="quote-sheet-title">{quote.storeName}</h2>
          <button
            ref={closeRef}
            type="button"
            className="icon-btn"
            aria-label={t('quote.close')}
            onClick={onClose}
            disabled={busy}
          >
            <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
              <path d="M6 6l12 12M18 6L6 18" />
            </svg>
          </button>
        </div>
        <p className="muted small">
          {where} · {t('quote.readyIn', { n: quote.packingTimeMins })}
        </p>
        <p className="small">{availabilityText(quote, t, tn)}</p>

        <QuoteLines lines={quote.items} total={quote.totalPrice} />

        {error && (
          <p className="form-error" role="alert">
            {error}
          </p>
        )}

        {canAccept &&
          (confirming ? (
            <div className="card accept-confirm">
              <p className="label">
                {t('accept.confirmTitle', { price: formatINR(quote.totalPrice), store: quote.storeName })}
              </p>
              <p className="small">
                {t('accept.details', { n: quote.packingTimeMins })}
              </p>
              <button type="button" className="btn btn-block btn-tall" onClick={accept} disabled={busy} aria-busy={busy}>
                {busy && <span className="spinner" aria-hidden="true" />}
                {busy ? t('common.wait') : t('accept.confirm', { price: formatINR(quote.totalPrice) })}
              </button>
              <button
                type="button"
                className="btn btn-secondary btn-block"
                onClick={() => {
                  setConfirming(false);
                  setError(null);
                }}
                disabled={busy}
              >
                {t('common.goBack')}
              </button>
            </div>
          ) : (
            <button
              type="button"
              className="btn btn-block btn-tall"
              onClick={() => {
                setConfirming(true);
                setError(null);
              }}
            >
              {t('quote.accept')}
            </button>
          ))}
      </div>
    </div>
  );
}
