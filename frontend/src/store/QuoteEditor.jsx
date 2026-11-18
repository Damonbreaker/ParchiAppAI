import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { putQuote, scanShopParchi } from '../api/endpoints.js';
import { errorText } from '../components/ErrorBox.jsx';
import PhotoPicker from '../customer/PhotoPicker.jsx';
import QuoteLineRow from './QuoteLineRow.jsx';
import { compressImage } from '../lib/compressImage.js';
import { formatPaise } from '../lib/money.js';
import {
  allUnavailable,
  buildRows,
  cleanNumber,
  mapServerErrors,
  NO_ERRORS,
  PACKING_CHIPS,
  packingError,
  rowErrors,
  totalPaise,
  toPayload,
  withStatus,
} from '../lib/quote.js';
import { applyShopScan } from '../lib/scanMatch.js';
import { useT } from '../i18n/useT.js';

// Price editor for one request. onSent(quote) after a successful PUT,
// onClosed() on 409 REQUEST_CLOSED, onCancel() discards edits (only when editing).
export default function QuoteEditor({ request, myQuote, onSent, onClosed, onCancel }) {
  const { t } = useT();
  const [rows, setRows] = useState(() => buildRows(request.items, myQuote));
  const initialPacking = myQuote ? String(myQuote.packingTimeMins) : '';
  const [packing, setPacking] = useState(initialPacking);
  const [customPacking, setCustomPacking] = useState(
    () => Boolean(myQuote) && !PACKING_CHIPS.includes(myQuote.packingTimeMins)
  );
  const [showErrors, setShowErrors] = useState(false);
  const [serverErrors, setServerErrors] = useState({ rows: {}, packing: null });
  const [formError, setFormError] = useState(null);
  const [scanNote, setScanNote] = useState(null);
  const [scanBusy, setScanBusy] = useState(false);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const scanBusyRef = useRef(false);
  const rowEls = useRef(new Map());
  const packingRef = useRef(null);
  const customRef = useRef(null);

  // Stable callbacks so memoised rows skip re-rendering.
  const registerRow = useCallback((id, el) => {
    if (el) rowEls.current.set(id, el);
    else rowEls.current.delete(id);
  }, []);

  const clearServerError = useCallback((id) => {
    setServerErrors((s) => {
      if (!s.rows[id]) return s;
      const next = { ...s.rows };
      delete next[id];
      return { ...s, rows: next };
    });
  }, []);

  const onStatus = useCallback(
    (id, status) => {
      setRows((rs) => rs.map((r) => (r.id === id ? withStatus(r, status) : r)));
      clearServerError(id);
      setFormError(null);
    },
    [clearServerError]
  );

  const onField = useCallback(
    (id, field, value) => {
      setRows((rs) => rs.map((r) => (r.id === id ? { ...r, [field]: value } : r)));
      clearServerError(id);
    },
    [clearServerError]
  );

  const onOpenQty = useCallback((id) => {
    setRows((rs) => rs.map((r) => (r.id === id ? { ...r, qtyOpen: true } : r)));
  }, []);

  // Enter on a price jumps to the next visible price box, then to packing time.
  const rowsRef = useRef(rows);
  useEffect(() => {
    rowsRef.current = rows;
  }, [rows]);
  const onPriceEnter = useCallback((id) => {
    const list = rowsRef.current;
    const i = list.findIndex((r) => r.id === id);
    for (let j = i + 1; j < list.length; j++) {
      const input = rowEls.current.get(list[j].id)?.querySelector('[data-price]');
      if (input) {
        input.focus();
        return;
      }
    }
    packingRef.current?.querySelector('button, input')?.focus();
  }, []);

  const total = useMemo(() => totalPaise(rows), [rows]);
  const noneOffered = allUnavailable(rows);
  const packingErr = (showErrors && packingError(packing)) || serverErrors.packing;

  function choosePacking(mins) {
    setCustomPacking(false);
    setPacking(String(mins));
    setServerErrors((s) => ({ ...s, packing: null }));
  }

  // Scrolls to a row (or the packing card) and focuses the bad input. Runs before
  // React re-renders the error state, so it targets inputs by role, not aria-invalid.
  function focusFirstError(rowId, slot) {
    const el = rowId != null ? rowEls.current.get(rowId) : packingRef.current;
    if (!el) return;
    el.scrollIntoView({ block: 'center' });
    const selector =
      rowId == null ? 'input, [aria-checked="true"], button' : slot === 'qty' ? 'input.item-qty, select' : '[data-price]';
    el.querySelector(selector)?.focus({ preventScroll: true });
  }

  async function send() {
    if (busyRef.current) return;
    setShowErrors(true);
    setFormError(null);

    const firstBad = rows.find((r) => rowErrors(r) !== NO_ERRORS);
    if (firstBad) return focusFirstError(firstBad.id, rowErrors(firstBad).qty ? 'qty' : 'price');
    if (noneOffered) {
      setFormError(t('quote.err.noneAvailable'));
      return;
    }
    if (packingError(packing)) return focusFirstError(null);

    busyRef.current = true;
    setBusy(true);
    try {
      const quote = await putQuote(request.id, toPayload(rows, packing));
      onSent(quote);
    } catch (err) {
      busyRef.current = false;
      setBusy(false);
      if (err.code === 'REQUEST_CLOSED') {
        onClosed();
        return;
      }
      if (err.code === 'VALIDATION_ERROR' && err.fields) {
        const mapped = mapServerErrors(err.fields, rows);
        setServerErrors(mapped);
        const first = rows.find((r) => mapped.rows[r.id]);
        if (first) focusFirstError(first.id, mapped.rows[first.id].qty ? 'qty' : 'price');
        else if (mapped.packing) focusFirstError(null);
      }
      setFormError(errorText(err, t));
    }
  }

  async function onScanList(file) {
    if (scanBusyRef.current || busyRef.current) return;
    scanBusyRef.current = true;
    setScanBusy(true);
    setScanNote(null);
    setFormError(null);
    try {
      const compressed = await compressImage(file);
      const result = await scanShopParchi(compressed.blob, { timeout: 180000 });
      const items = (result && result.items) || [];
      if (!items.length) {
        setScanNote(t('quote.scanEmpty'));
        return;
      }
      const applied = applyShopScan(rowsRef.current, items);
      setRows(applied.rows);
      setScanNote(t('quote.scanApplied', { matched: applied.matched, total: items.length }));
    } catch (err) {
      setFormError(errorText(err, t));
    } finally {
      scanBusyRef.current = false;
      setScanBusy(false);
    }
  }

  return (
    <div className="quote-editor">
      <div className="section-head">
        <h3>{t(myQuote ? 'quote.editTitle' : 'quote.title')}</h3>
        {onCancel && (
          <button type="button" className="link-btn" onClick={onCancel} disabled={busy || scanBusy}>
            {t('common.cancel')}
          </button>
        )}
      </div>
      <p className="muted small">{t('quote.hint')}</p>

      <div className="card note shop-scan">
        <p className="label">{t('quote.scanTitle')}</p>
        <p className="muted small">{t('quote.scanHint')}</p>
        {scanBusy ? (
          <p className="label scan-reading">
            <span className="spinner" aria-hidden="true" />
            {t('quote.scanning')}
          </p>
        ) : (
          <PhotoPicker onPick={onScanList} compact />
        )}
        {scanNote && <p className="muted small">{scanNote}</p>}
      </div>

      <ul className="item-list">
        {rows.map((row, index) => {
          const errs = showErrors ? rowErrors(row) : NO_ERRORS;
          const server = serverErrors.rows[row.id] || NO_ERRORS;
          return (
            <QuoteLineRow
              key={row.id}
              row={row}
              index={index}
              qtyError={errs.qty || server.qty}
              priceError={errs.price || server.price}
              onStatus={onStatus}
              onField={onField}
              onOpenQty={onOpenQty}
              onPriceEnter={onPriceEnter}
              registerRow={registerRow}
            />
          );
        })}
      </ul>

      <div ref={packingRef} className={packingErr ? 'card packing has-error' : 'card packing'}>
        <p className="label" id="packing-label">
          {t('quote.packingTime')}
        </p>
        <div className="chips" role="radiogroup" aria-labelledby="packing-label">
          {PACKING_CHIPS.map((mins) => (
            <button
              key={mins}
              type="button"
              role="radio"
              aria-checked={!customPacking && packing === String(mins)}
              className="chip"
              onClick={() => choosePacking(mins)}
            >
              {t('quote.mins', { n: mins })}
            </button>
          ))}
          <button
            type="button"
            role="radio"
            aria-checked={customPacking}
            className="chip"
            onClick={() => {
              setCustomPacking(true);
              // Wait for the box to render, then open the number keypad.
              requestAnimationFrame(() => customRef.current?.focus());
            }}
          >
            {t('quote.otherTime')}
          </button>
        </div>
        {customPacking && (
          <label className="packing-custom">
            <input
              className={packingErr ? 'input input-error' : 'input'}
              inputMode="numeric"
              ref={customRef}
              maxLength={3}
              aria-label={t('quote.packingTime')}
              aria-invalid={packingErr ? true : undefined}
              value={packing}
              onChange={(e) => {
                setPacking(cleanNumber(e.target.value).replace(/\D/g, ''));
                setServerErrors((s) => ({ ...s, packing: null }));
              }}
            />
            <span>{t('quote.minutes')}</span>
          </label>
        )}
        {packingErr && <p className="field-error">{t(packingErr)}</p>}
      </div>

      {formError && (
        <p className="form-error" role="alert">
          {formError}
        </p>
      )}

      <div className="sticky-action quote-footer">
        <span className="quote-footer-total">
          <span className="small muted">{t('quote.total')}</span>
          <span className="quote-total-amount">{formatPaise(total)}</span>
        </span>
        <button type="button" className="btn quote-send" onClick={send} disabled={busy} aria-busy={busy}>
          {busy && <span className="spinner" aria-hidden="true" />}
          {busy ? t('quote.sending') : t(myQuote ? 'quote.update' : 'quote.send')}
        </button>
      </div>
    </div>
  );
}
