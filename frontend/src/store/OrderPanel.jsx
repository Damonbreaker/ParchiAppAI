import { useCallback, useEffect, useRef, useState } from 'react';
import { deliverPoolBag, getStoreRequestRatings, markReady, rateCustomer } from '../api/endpoints.js';
import { errorText } from '../components/ErrorBox.jsx';
import RatingForm from '../components/RatingForm.jsx';
import StatusBadge from '../components/StatusBadge.jsx';
import StatusSteps from '../components/StatusSteps.jsx';
import PickupSheet from './PickupSheet.jsx';
import { formatINR } from '../lib/money.js';
import { isChangedLine, mergeLines } from '../lib/quote.js';
import { formatTime } from '../lib/time.js';
import { formatQty } from '../lib/units.js';
import { useHistoryOverlay } from '../lib/useHistoryOverlay.js';
import { usePolling } from '../lib/usePolling.js';
import { useT } from '../i18n/useT.js';

// Winning store's view of an order: who, by when, a packing checklist and the
// Ready / Picked up actions. onUpdate(storeDetail) after each server change,
// onReload() when the server says the status moved on without us.
export default function OrderPanel({ request, onUpdate, onReload }) {
  const { t, lang } = useT();
  const { status, customer, myQuote } = request;
  const [packed, setPacked] = useState(() => new Set()); // ticks live on this screen only
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  // In history state: the phone's Back button closes the code sheet.
  const [pickupOpen, openPickup, closePickup] = useHistoryOverlay('pickup');
  const busyRef = useRef(false);
  const [customerRating, setCustomerRating] = useState(null);
  const [rateBusy, setRateBusy] = useState(false);
  const [rateError, setRateError] = useState(null);
  const [bagCodes, setBagCodes] = useState({});
  const done = status === 'PICKED_UP';
  const bags = request.bags || [];
  const isPool = bags.length > 0;

  // Demo: poll until pickup OTP is revealed (~4s after ready/accept).
  const waitingOtp =
    !done && !isPool && (status === 'ACCEPTED' || status === 'READY') && !request.pickupCode;
  usePolling(() => onReload(), 2000, waitingOtp);

  useEffect(() => {
    if (!done) return undefined;
    const controller = new AbortController();
    getStoreRequestRatings(request.id, { signal: controller.signal })
      .then((map) => setCustomerRating(map?.customer || null))
      .catch(() => {});
    return () => controller.abort();
  }, [done, request.id]);

  const lines = mergeLines(request.items, myQuote);
  const toPack = lines.filter((l) => l.status !== 'UNAVAILABLE');
  const missing = lines.filter((l) => l.status === 'UNAVAILABLE');
  const total = myQuote ? formatINR(myQuote.totalPrice) : '';

  function toggle(id) {
    setPacked((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  async function ready() {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    setError(null);
    try {
      onUpdate(await markReady(request.id));
    } catch (err) {
      setError(errorText(err, t));
      if (err.code === 'INVALID_STATUS') onReload();
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  }

  async function deliverBag(memberId) {
    if (busyRef.current) return;
    const code = String(bagCodes[memberId] || '').replace(/\D/g, '').slice(0, 4);
    if (code.length !== 4) {
      setError(t('pickup.err.length'));
      return;
    }
    busyRef.current = true;
    setBusy(true);
    setError(null);
    try {
      onUpdate(await deliverPoolBag(request.id, { memberId, pickupCode: code }));
      setBagCodes((prev) => ({ ...prev, [memberId]: '' }));
    } catch (err) {
      setError(errorText(err, t));
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  }

  const pickedUp = useCallback(
    (updated) => {
      closePickup();
      onUpdate(updated);
      window.scrollTo(0, 0);
    },
    [onUpdate, closePickup]
  );

  const qty = (q, u) => `${formatQty(q)} ${t(`unit.${u}`)}`;

  return (
    <>
      <div className="section-head">
        <h2>{t('order.title', { id: request.id })}</h2>
        <StatusBadge status={status} />
      </div>

      {done && (
        <div className="card done-card thank-you-card" role="status">
          <p className="done-title">✓ {t('order.thankYouStoreTitle')}</p>
          <p className="muted">{t('order.thankYouStoreBody', { name: customer?.name || t('order.theCustomer') })}</p>
          <p className="small muted">
            {request.pickedUpAt && t('order.pickedUpAt', { time: formatTime(request.pickedUpAt, lang) })}
            {total && ` · ${t('order.collected', { price: total })}`}
          </p>
        </div>
      )}

      <StatusSteps order={request} />

      {!done && request.pickupCode && (
        <div className="card pickup-code-card demo-otp" role="status">
          <p className="label">{t('order.demoOtpTitle')}</p>
          <p className="pickup-code" aria-label={request.pickupCode.split('').join(' ')}>
            {request.pickupCode.split('').map((d, i) => (
              <span key={i} className="pickup-digit" aria-hidden="true">
                {d}
              </span>
            ))}
          </p>
          <p className="small muted">{t('order.demoOtpHint')}</p>
        </div>
      )}
      {!done && waitingOtp && (
        <div className="card note" role="status">
          <p className="label">
            <span className="spinner spinner-sm" aria-hidden="true" /> {t('order.demoOtpWaiting')}
          </p>
        </div>
      )}

      {customer && (
        <div className="card store-contact">
          <p className="small muted">{t(isPool ? 'pool.shopCluster' : 'order.customer')}</p>
          <p className="target-name">{customer.name}</p>
          {!done && !isPool && customer.phone && (
            <div className="contact-actions">
              <a className="btn btn-secondary" href={`tel:${customer.phone}`}>
                {t('order.call')}
              </a>
            </div>
          )}
          {isPool && <p className="muted small">{t('pool.shopAnonymous')}</p>}
        </div>
      )}

      {status === 'ACCEPTED' && request.readyBy && (
        <p className="ready-by">{t('order.readyBy', { time: formatTime(request.readyBy, lang) })}</p>
      )}

      {isPool ? (
        <>
          <div className="section-head">
            <h3>{t('pool.packByNeighbour')}</h3>
          </div>
          {bags.map((bag) => (
            <div key={bag.memberId} className="card" style={{ marginBottom: '0.75rem' }}>
              <p className="target-name">
                {bag.alias}
                {bag.delivered ? ` · ✓ ${t('pool.bagDelivered')}` : ''}
              </p>
              <ul className="plain-list">
                {(bag.items || []).map((it, i) => (
                  <li key={i} className="muted small">
                    {it.name} · {formatQty(it.quantity)} {t(`unit.${it.unit}`)}
                  </li>
                ))}
              </ul>
              {!done && !bag.delivered && (status === 'ACCEPTED' || status === 'READY') && (
                <div className="item-row-bottom" style={{ marginTop: '0.5rem' }}>
                  <input
                    className="input item-qty"
                    inputMode="numeric"
                    maxLength={4}
                    placeholder={t('pool.bagOtpPlaceholder')}
                    value={bagCodes[bag.memberId] || ''}
                    onChange={(e) =>
                      setBagCodes((prev) => ({
                        ...prev,
                        [bag.memberId]: e.target.value.replace(/\D/g, '').slice(0, 4),
                      }))
                    }
                  />
                  <button
                    type="button"
                    className="btn btn-secondary"
                    disabled={busy}
                    onClick={() => deliverBag(bag.memberId)}
                  >
                    {t('pool.confirmBag')}
                  </button>
                </div>
              )}
            </div>
          ))}
        </>
      ) : (
        <>
          <div className="section-head">
            <h3>{t(done ? 'order.items' : 'order.packList')}</h3>
            {!done && (
              <span className="small muted">{t('order.packedCount', { n: packed.size, total: toPack.length })}</span>
            )}
          </div>
          <ul className="card plain-list pack-list">
            {toPack.map((line) => {
              const isPacked = packed.has(line.requestItemId);
              return (
                <li key={line.requestItemId}>
                  <label className={isPacked ? 'pack-item is-packed' : 'pack-item'}>
                    {!done && (
                      <input type="checkbox" checked={isPacked} onChange={() => toggle(line.requestItemId)} />
                    )}
                    <span className="quote-line-main">
                      <span className="quote-line-name">{line.name}</span>
                      <span className={isChangedLine(line) ? 'small quote-line-change' : 'small muted'}>
                        {qty(line.offeredQty, line.unit)}
                      </span>
                    </span>
                    <span className="line-qty">{formatINR(line.linePrice)}</span>
                  </label>
                </li>
              );
            })}
          </ul>
          {missing.length > 0 && (
            <p className="small muted">{t('order.notGiving', { items: missing.map((l) => l.name).join(', ') })}</p>
          )}
        </>
      )}

      <div className="card collect-card">
        <span className="label">{t(done ? 'order.totalPaid' : 'order.collectLabel')}</span>
        <span className="quote-total-amount">{total}</span>
      </div>

      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}

      {!done && (
        <div className="sticky-action order-actions">
          {status === 'ACCEPTED' && (
            <button type="button" className="btn btn-block" onClick={ready} disabled={busy} aria-busy={busy}>
              {busy && <span className="spinner" aria-hidden="true" />}
              {busy ? t('common.wait') : t('order.markReady')}
            </button>
          )}
          {!isPool && (
            <button
              type="button"
              className={status === 'READY' ? 'btn btn-block' : 'btn btn-secondary btn-block'}
              onClick={openPickup}
              disabled={busy}
            >
              {t('order.pickedUpBtn')}
            </button>
          )}
          {isPool && <p className="muted small">{t('pool.shopDeliverHint')}</p>}
        </div>
      )}

      {done && !customerRating && (
        <>
          {rateError && (
            <p className="form-error" role="alert">
              {rateError}
            </p>
          )}
          <RatingForm
            target="customer"
            busy={rateBusy}
            onSubmit={async (body) => {
              setRateBusy(true);
              setRateError(null);
              try {
                setCustomerRating(await rateCustomer(request.id, body));
              } catch (err) {
                setRateError(errorText(err, t));
              } finally {
                setRateBusy(false);
              }
            }}
          />
        </>
      )}
      {done && customerRating && (
        <div className="card note" role="status">
          <p className="label">{t('rating.thanks')}</p>
          <p className="muted small">
            {'★'.repeat(customerRating.stars)}
            {customerRating.qualities?.length
              ? ` · ${customerRating.qualities.map((q) => t(`rating.tag.${q}`)).join(', ')}`
              : ''}
          </p>
        </div>
      )}

      {pickupOpen && (
        <PickupSheet
          requestId={request.id}
          onDone={pickedUp}
          onClose={closePickup}
          initialCode={request.pickupCode || ''}
        />
      )}
    </>
  );
}
