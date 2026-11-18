import { memo } from 'react';
import { formatKm } from '../lib/geo.js';
import { formatINR } from '../lib/money.js';
import { timeAgo } from '../lib/time.js';
import { useT } from '../i18n/useT.js';

// "8 of 10 items · 1 less · 1 not available"
export function availabilityText(quote, t, tn) {
  const total = quote.availableCount + quote.partialCount + quote.unavailableCount;
  const parts = [tn('quote.availOf', total, { a: quote.availableCount })];
  if (quote.partialCount) parts.push(t('quote.lessCount', { n: quote.partialCount }));
  if (quote.unavailableCount) parts.push(t('quote.naCount', { n: quote.unavailableCount }));
  return parts.join(' · ');
}

// One store's price on the customer's request page — Accept is the primary action.
function QuoteCard({ quote, lowest, canAccept, onOpen, onAccept }) {
  const { t, tn, lang } = useT();
  return (
    <article className={lowest ? 'quote-card is-lowest' : 'quote-card'}>
      <button type="button" className="quote-card-body" onClick={() => onOpen(quote.quoteId)}>
        <span className="quote-card-top">
          <span className="quote-card-store">
            <span className="target-name">{quote.storeName}</span>
            <span className="small muted">
              {quote.area}
              {quote.distanceKm != null && ` · ${t('unit.km', { km: formatKm(quote.distanceKm) })}`}
            </span>
          </span>
          <span className="quote-card-price">
            {lowest && <span className="badge badge-lowest">{t('quote.lowest')}</span>}
            <span className="quote-total-amount">{formatINR(quote.totalPrice)}</span>
          </span>
        </span>
        <span className="quote-card-meta">
          <span className="label">{t('quote.readyIn', { n: quote.packingTimeMins })}</span>
          <span className="small muted">{t('quote.updated', { ago: timeAgo(quote.updatedAt, lang) })}</span>
        </span>
        <span className="small">{availabilityText(quote, t, tn)}</span>
        <span className="quote-card-hint muted small">{t('quote.tapDetails')}</span>
      </button>
      {canAccept && (
        <div className="quote-card-actions">
          <button
            type="button"
            className="btn btn-block"
            onClick={() => onAccept?.(quote)}
          >
            {t('quote.acceptPrice', { price: formatINR(quote.totalPrice) })}
          </button>
        </div>
      )}
    </article>
  );
}

export default memo(QuoteCard);
