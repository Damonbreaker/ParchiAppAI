import { useMemo, useState } from 'react';
import FactTicker from '../components/FactTicker.jsx';
import QuoteCard from '../components/QuoteCard.jsx';
import ShareParchiCard from '../components/ShareParchiCard.jsx';
import { isDemoBuild } from '../lib/geo.js';
import { formatINR } from '../lib/money.js';
import { getShareSnapshot } from '../lib/shareSnapshot.js';
import { useT } from '../i18n/useT.js';

const price = (q) => Number(q.totalPrice);
const byPrice = (a, b) => price(a) - price(b);
const dist = (q) => (q.distanceKm == null ? Infinity : q.distanceKm);

const SORTS = {
  cheapest: byPrice,
  fastest: (a, b) => a.packingTimeMins - b.packingTimeMins || byPrice(a, b),
  nearest: (a, b) => dist(a) - dist(b) || byPrice(a, b),
};
const SORT_KEYS = { cheapest: 'quote.sortCheapest', fastest: 'quote.sortFastest', nearest: 'quote.sortNearest' };

// "Prices from stores" on the customer's request page.
export default function QuotesSection({ request, onOpen, onAskAccept }) {
  const { t, tn } = useT();
  const [sort, setSort] = useState('cheapest');
  const quotes = request.quotes || [];
  const isOpen = request.status === 'OPEN';
  const targetCount = (request.targets || []).length;

  const sorted = useMemo(() => [...quotes].sort(SORTS[sort]), [quotes, sort]);
  const lowest = quotes.length ? Math.min(...quotes.map(price)) : null;
  const best = useMemo(() => (quotes.length ? [...quotes].sort(byPrice)[0] : null), [quotes]);
  const replied = tn('quote.replied', targetCount, { n: quotes.length, total: targetCount });

  return (
    <>
      <h3>{t('quote.fromStores')}</h3>

      {quotes.length === 0 ? (
        isOpen ? (
          <div className="card note" role="status">
            <p className="label">
              <span className="spinner spinner-sm" aria-hidden="true" /> {t('detail.waiting')} {replied}
            </p>
            <p className="muted small">{t('quote.waitingHint')}</p>
            {isDemoBuild() ? <p className="muted small">{t('quote.demoAutoHint')}</p> : null}
            <FactTicker />
          </div>
        ) : (
          <div className="card empty">
            <p className="muted">{t('quote.noneSent')}</p>
          </div>
        )
      ) : (
        <>
          <p className="muted small" role="status">
            {isOpen && quotes.length < targetCount ? `${t('detail.waiting')} ${replied}` : replied}
          </p>
          {isOpen && best && (
            <div className="card accept-best" role="region" aria-label={t('quote.acceptBestRegion')}>
              <p className="label">{t('quote.acceptBestTitle', { store: best.storeName })}</p>
              <p className="accept-best-price">{formatINR(best.totalPrice)}</p>
              <p className="muted small">{t('quote.readyIn', { n: best.packingTimeMins })}</p>
              <button type="button" className="btn btn-block btn-tall" onClick={() => onAskAccept(best)}>
                {t('quote.acceptPrice', { price: formatINR(best.totalPrice) })}
              </button>
            </div>
          )}
          {getShareSnapshot() && quotes.length > 0 && (
            <ShareParchiCard quoteCount={quotes.length} lowestPrice={lowest} compact />
          )}
          {quotes.length > 1 && (
            <div className="chips" role="radiogroup" aria-label={t('quote.sortBy')}>
              {Object.keys(SORTS).map((key) => (
                <button key={key} type="button" role="radio" aria-checked={sort === key} className="chip" onClick={() => setSort(key)}>
                  {t(SORT_KEYS[key])}
                </button>
              ))}
            </div>
          )}
          <div className="store-list">
            {sorted.map((quote) => (
              <QuoteCard
                key={quote.quoteId}
                quote={quote}
                lowest={quotes.length > 1 && price(quote) === lowest}
                canAccept={isOpen}
                onOpen={onOpen}
                onAccept={onAskAccept}
              />
            ))}
          </div>
        </>
      )}
    </>
  );
}
