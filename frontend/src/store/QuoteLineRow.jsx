import { memo } from 'react';
import { qtyEditable, STATUSES } from '../lib/quote.js';
import { formatQty, UNITS } from '../lib/units.js';
import { useT } from '../i18n/useT.js';

const STATUS_KEYS = { AVAILABLE: 'quote.status.AVAILABLE', PARTIAL: 'quote.status.PARTIAL', UNAVAILABLE: 'quote.status.UNAVAILABLE' };

// One item card in the quote editor. Memoised like ItemRow: the parent keeps
// every callback stable, passes errors as strings, and only replaces the
// edited row's object, so typing re-renders just this row even with 50 items.
function QuoteLineRow({ row, index, qtyError, priceError, onStatus, onField, onOpenQty, onPriceEnter, registerRow }) {
  const { t } = useT();
  const unavailable = row.status === 'UNAVAILABLE';
  const editable = qtyEditable(row);
  const hasError = Boolean(qtyError || priceError);
  const base = `ql-${row.id}`;

  return (
    <li
      ref={(el) => registerRow(row.id, el)}
      className={`quote-row${unavailable ? ' is-unavailable' : ''}${hasError ? ' has-error' : ''}`}
    >
      <p className="quote-row-asked">
        <span className="quote-row-name">{row.name}</span>
        <span className="muted"> — {formatQty(row.askedQty)} {t(`unit.${row.askedUnit}`)}</span>
      </p>

      <div className="seg" role="radiogroup" aria-label={t('quote.statusFor', { n: index + 1, name: row.name })}>
        {STATUSES.map((s) => (
          <button
            key={s}
            type="button"
            role="radio"
            aria-checked={row.status === s}
            className={`seg-btn seg-${s.toLowerCase()}`}
            onClick={() => onStatus(row.id, s)}
          >
            {t(STATUS_KEYS[s])}
          </button>
        ))}
      </div>

      {!unavailable && (
        <div className="quote-row-fields">
          <div className="quote-qty">
            <span className="small muted" id={`${base}-qlabel`}>
              {t('quote.youGive')}
            </span>
            {editable ? (
              <div className="quote-qty-inputs">
                <input
                  className={qtyError ? 'input item-qty input-error' : 'input item-qty'}
                  inputMode="decimal"
                  maxLength={12}
                  aria-labelledby={`${base}-qlabel`}
                  aria-invalid={qtyError ? true : undefined}
                  aria-describedby={qtyError ? `${base}-qerr` : undefined}
                  value={row.qty}
                  onChange={(e) => onField(row.id, 'qty', e.target.value)}
                />
                <select
                  className="input item-unit"
                  aria-label={t('newList.unit')}
                  value={row.unit}
                  onChange={(e) => onField(row.id, 'unit', e.target.value)}
                >
                  {UNITS.map((u) => (
                    <option key={u} value={u}>
                      {t(`unit.${u}`)}
                    </option>
                  ))}
                </select>
              </div>
            ) : (
              <div className="quote-qty-fixed">
                <span className="label">
                  {formatQty(row.qty)} {t(`unit.${row.unit}`)}
                </span>
                <button type="button" className="link-btn" onClick={() => onOpenQty(row.id)}>
                  {t('quote.change')}
                </button>
              </div>
            )}
          </div>

          <label className="quote-price">
            <span className="small muted">{t('quote.linePrice')}</span>
            <span className="price-input">
              <span aria-hidden="true">₹</span>
              <input
                className={priceError ? 'input input-error' : 'input'}
                inputMode="decimal"
                enterKeyHint="next"
                maxLength={10}
                placeholder="0"
                aria-invalid={priceError ? true : undefined}
                aria-describedby={priceError ? `${base}-perr` : undefined}
                data-price
                value={row.price}
                onChange={(e) => onField(row.id, 'price', e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') {
                    e.preventDefault();
                    onPriceEnter(row.id);
                  }
                }}
              />
            </span>
          </label>
        </div>
      )}

      {!unavailable && qtyError && (
        <p id={`${base}-qerr`} className="field-error">
          {t(qtyError)}
        </p>
      )}
      {!unavailable && priceError && (
        <p id={`${base}-perr`} className="field-error">
          {t(priceError)}
        </p>
      )}
    </li>
  );
}

export default memo(QuoteLineRow);
