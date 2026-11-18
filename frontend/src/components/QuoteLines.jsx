import { formatINR } from '../lib/money.js';
import { isChangedLine } from '../lib/quote.js';
import { formatQty } from '../lib/units.js';
import { useT } from '../i18n/useT.js';

// Asked vs offered, line by line, with the total at the bottom.
// Line: { requestItemId, name, askedQty, askedUnit, offeredQty, unit, linePrice, status }
export default function QuoteLines({ lines, total }) {
  const { t } = useT();
  const qty = (q, u) => `${formatQty(q)} ${t(`unit.${u}`)}`;

  return (
    <ul className="card plain-list quote-lines">
      {lines.map((line) => {
        const unavailable = line.status === 'UNAVAILABLE';
        const changed = isChangedLine(line);
        const cls = unavailable ? 'quote-line is-unavailable' : changed ? 'quote-line is-changed' : 'quote-line';
        return (
          <li key={line.requestItemId} className={cls}>
            <span className="quote-line-main">
              <span className="quote-line-name">{line.name}</span>
              {unavailable ? (
                <span className="small">{t('quote.notAvailable')}</span>
              ) : changed ? (
                <span className="small quote-line-change">
                  {t('quote.askedChange', { asked: qty(line.askedQty, line.askedUnit), offered: qty(line.offeredQty, line.unit) })}
                </span>
              ) : (
                <span className="small muted">{qty(line.offeredQty, line.unit)}</span>
              )}
            </span>
            {!unavailable && <span className="line-qty">{formatINR(line.linePrice)}</span>}
          </li>
        );
      })}
      <li className="quote-line quote-lines-total">
        <span className="label">{t('quote.total')}</span>
        <span className="quote-total-amount">{formatINR(total)}</span>
      </li>
    </ul>
  );
}
