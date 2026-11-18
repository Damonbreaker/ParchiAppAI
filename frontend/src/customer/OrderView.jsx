import { useCallback, useEffect, useState } from 'react';
import { getRequestRatings, rateStore } from '../api/endpoints.js';
import QuoteLines from '../components/QuoteLines.jsx';
import RatingForm from '../components/RatingForm.jsx';
import StatusSteps from '../components/StatusSteps.jsx';
import { errorText } from '../components/ErrorBox.jsx';
import { formatINR } from '../lib/money.js';
import { formatTime } from '../lib/time.js';
import { useT } from '../i18n/useT.js';

// Google Maps directions to the store. Coordinates only, nothing about the user.
function directionsUrl({ lat, lng }) {
  return `https://www.google.com/maps/dir/?api=1&destination=${lat},${lng}`;
}

// Customer's request after a quote was accepted (ACCEPTED / READY / PICKED_UP).
export default function OrderView({ request }) {
  const { t, lang } = useT();
  const { status, acceptedStore: store } = request;
  const quotes = request.quotes || [];
  const accepted = quotes.find((q) => q.quoteId === request.acceptedQuoteId);
  const others = quotes.filter((q) => q.quoteId !== request.acceptedQuoteId);
  const pickedUp = status === 'PICKED_UP';
  const [mine, setMine] = useState(null);
  const [busy, setBusy] = useState(false);
  const [rateError, setRateError] = useState(null);

  const loadRatings = useCallback(() => {
    if (!pickedUp) return undefined;
    const controller = new AbortController();
    getRequestRatings(request.id, { signal: controller.signal })
      .then((map) => setMine(map?.store || null))
      .catch(() => {});
    return () => controller.abort();
  }, [pickedUp, request.id]);

  useEffect(loadRatings, [loadRatings]);

  async function submitRating(body) {
    setBusy(true);
    setRateError(null);
    try {
      const saved = await rateStore(request.id, body);
      setMine(saved);
    } catch (err) {
      setRateError(errorText(err, t));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      {status === 'READY' && (
        <div className="ready-banner" role="status">
          <p className="ready-banner-title">{t('order.readyBanner')}</p>
          {store && <p className="small">{store.name}</p>}
        </div>
      )}

      <StatusSteps order={request} />

      {status === 'ACCEPTED' && request.readyBy && (
        <p className="ready-by">{t('order.readyBy', { time: formatTime(request.readyBy, lang) })}</p>
      )}

      {pickedUp ? (
        <div className="card done-card thank-you-card" role="status">
          <p className="done-title">✓ {t('order.thankYouTitle')}</p>
          <p className="muted">{t('order.thankYouBody', { store: store?.name || t('order.theStore') })}</p>
          {request.pickedUpAt && (
            <p className="small muted">{t('order.pickedUpAt', { time: formatTime(request.pickedUpAt, lang) })}</p>
          )}
        </div>
      ) : (
        request.pickupCode && (
          <div className="card pickup-code-card">
            <p className="label">{t('order.pickupCode')}</p>
            <p className="pickup-code" aria-label={request.pickupCode.split('').join(' ')}>
              {request.pickupCode.split('').map((d, i) => (
                <span key={i} className="pickup-digit" aria-hidden="true">
                  {d}
                </span>
              ))}
            </p>
            <p className="small muted">{t('order.showCode')}</p>
          </div>
        )
      )}

      {pickedUp && !mine && (
        <>
          {rateError && (
            <p className="form-error" role="alert">
              {rateError}
            </p>
          )}
          <RatingForm target="store" onSubmit={submitRating} busy={busy} />
        </>
      )}
      {pickedUp && mine && (
        <div className="card note" role="status">
          <p className="label">{t('rating.thanks')}</p>
          <p className="muted small">
            {'★'.repeat(mine.stars)}
            {mine.qualities?.length ? ` · ${mine.qualities.map((q) => t(`rating.tag.${q}`)).join(', ')}` : ''}
          </p>
        </div>
      )}

      {store && (
        <div className="card store-contact">
          <p className="target-name">{store.name}</p>
          <p className="small muted">
            {store.address}
            {store.area && !store.address?.includes(store.area) && `, ${store.area}`}
          </p>
          <div className="contact-actions">
            {store.phone && (
              <a className="btn btn-secondary" href={`tel:${store.phone}`}>
                {t('order.call')}
              </a>
            )}
            <a className="btn btn-secondary" href={directionsUrl(store)} target="_blank" rel="noopener noreferrer">
              {t('order.directions')}
            </a>
          </div>
        </div>
      )}

      {accepted && (
        <>
          <h3>{t('order.youGet')}</h3>
          <QuoteLines lines={accepted.items} total={accepted.totalPrice} />
          {!pickedUp && <p className="pay-note">{t('order.payAtStore', { price: formatINR(accepted.totalPrice) })}</p>}
        </>
      )}

      {others.length > 0 && (
        <details className="card other-prices">
          <summary>{t('order.otherPrices', { n: others.length })}</summary>
          <ul className="plain-list">
            {others.map((q) => (
              <li key={q.quoteId} className="line-item">
                <span className="target-main">
                  <span>{q.storeName}</span>
                  <span className="small muted">{t('quote.readyIn', { n: q.packingTimeMins })}</span>
                </span>
                <span className="muted">{formatINR(q.totalPrice)}</span>
              </li>
            ))}
          </ul>
        </details>
      )}
    </>
  );
}
