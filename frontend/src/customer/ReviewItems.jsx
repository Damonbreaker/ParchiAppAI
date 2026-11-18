import { useRef, useState } from 'react';
import { Link, Navigate, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext.jsx';
import { createRequest, markSharedListPlaced } from '../api/endpoints.js';
import BackButton from '../components/BackButton.jsx';
import { errorText } from '../components/ErrorBox.jsx';
import WaitNote from '../components/WaitNote.jsx';
import { useRateLimit } from '../lib/useRateLimit.js';
import { cleanItems, clearDraft, loadDraft, saveDraft } from '../lib/draft.js';
import { ensureDemoLocation, formatCoords, getSavedLocation, isDemoBuild } from '../lib/geo.js';
import ShareParchiCard from '../components/ShareParchiCard.jsx';
import { formatINR, formatMrpEstimate } from '../lib/money.js';
import { getShareSnapshot } from '../lib/shareSnapshot.js';
import { formatItemQty } from '../lib/units.js';
import { useT } from '../i18n/useT.js';

export default function ReviewItems() {
  const { t, tn } = useT();
  const { user } = useAuth();
  const navigate = useNavigate();
  const [draft, setDraft] = useState(() => loadDraft(user.id));
  const [location] = useState(() => (isDemoBuild() ? ensureDemoLocation() : getSavedLocation()));
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const limit = useRateLimit('send');

  const draftItems = draft?.items || [];
  const items = draft ? cleanItems(draftItems).items : [];
  const mrpTotal = draftItems.reduce((sum, row) => {
    const amount = row?.mrp?.amount;
    return sum + (amount != null && Number(amount) > 0 ? Number(amount) : 0);
  }, 0);
  if (items.length === 0) return <Navigate to="/c/new/edit" replace />;
  if (!location) return <Navigate to="/c/location" replace state={{ returnTo: '/c/new/review' }} />;

  const canDirect = Boolean(draft.storeId);
  const mode = canDirect && draft.mode === 'DIRECT' ? 'DIRECT' : 'NEAREST';

  function setMode(next) {
    const updated = { ...draft, mode: next };
    setDraft(updated);
    saveDraft(user.id, updated);
    setError(null);
  }

  async function send(sendMode = mode) {
    if (busyRef.current || limit.locked) return;
    busyRef.current = true;
    setBusy(true);
    setError(null);
    try {
      const created = await createRequest({
        items,
        lat: location.lat,
        lng: location.lng,
        mode: sendMode,
        ...(sendMode === 'DIRECT' ? { storeId: draft.storeId } : {}),
        ...(draft.fromPhoto ? { fromPhoto: true } : {}),
      });
      if (draft.sharedToken) {
        try {
          await markSharedListPlaced(draft.sharedToken);
        } catch {
          // Order already created; ignore list mark failure.
        }
      }
      clearDraft(user.id);
      navigate(`/c/requests/${created.id}`, { replace: true });
    } catch (err) {
      setError(limit.check(err) ? null : err);
      busyRef.current = false;
      setBusy(false);
    }
  }

  function sendToNearest() {
    setMode('NEAREST');
    send('NEAREST');
  }

  let errorBox = null;
  if (error?.code === 'NO_STORES_NEARBY') {
    errorBox = (
      <div className="form-error" role="alert">
        <p>{t('review.noStoresNearby')}</p>
        <Link to="/c/location" state={{ returnTo: '/c/new/review' }} className="link-btn">
          {t('review.changeLocation')}
        </Link>
      </div>
    );
  } else if (error?.code === 'STORE_CLOSED') {
    errorBox = (
      <div className="form-error" role="alert">
        <p>{t('review.storeClosed', { store: draft.storeName })}</p>
        <button type="button" className="btn btn-block" onClick={sendToNearest} disabled={busy}>
          {t('review.sendNearestInstead')}
        </button>
      </div>
    );
  } else if (error) {
    errorBox = (
      <p className="form-error" role="alert">
        {error.fields?.items || errorText(error, t)}
      </p>
    );
  }

  return (
    <section className="screen">
      <BackButton fallback={draft.sharedToken ? `/c/shared/${draft.sharedToken}` : '/c/new/edit'} />
      <h2>{t('review.title')}</h2>
      {draft.sharedToken && (
        <div className="card note" role="note">
          <p className="label">{t('group.reviewBanner')}</p>
          <p className="muted small">{t('group.reviewBannerHint')}</p>
        </div>
      )}
      {draft.fromPhoto && getShareSnapshot() && <ShareParchiCard compact />}

      <div className="section-head">
        <h3>{tn('requests.items', items.length)}</h3>
        <Link to="/c/new/edit" className="link-btn">
          {t('review.edit')}
        </Link>
      </div>
      <ul className="card plain-list">
        {items.map((item, i) => {
          const source = draftItems.find((r) => String(r.name || '').trim() === item.name) || draftItems[i] || {};
          const mrpText = formatMrpEstimate(source.mrp);
          return (
            <li key={i} className="line-item">
              <span>
                {item.name}
                {mrpText ? (
                  <span className="muted small"> · {t('catalogue.mrpEstimate', { price: mrpText })}</span>
                ) : null}
              </span>
              <span className="muted">{formatItemQty(item, t)}</span>
            </li>
          );
        })}
      </ul>
      {mrpTotal > 0 && (
        <p className="muted small">{t('review.mrpTotal', { price: formatINR(mrpTotal) })}</p>
      )}

      <h3>{t('review.whoGetsIt')}</h3>
      <div className="choice-list" role="radiogroup" aria-label={t('review.whoGetsIt')}>
        <label className={mode === 'NEAREST' ? 'choice is-selected' : 'choice'}>
          <input type="radio" name="mode" checked={mode === 'NEAREST'} onChange={() => setMode('NEAREST')} disabled={busy} />
          <span>
            <span className="choice-title">{t('review.nearest')}</span>
            <span className="muted small">{t('review.nearestHint')}</span>
          </span>
        </label>
        {canDirect && (
          <label className={mode === 'DIRECT' ? 'choice is-selected' : 'choice'}>
            <input type="radio" name="mode" checked={mode === 'DIRECT'} onChange={() => setMode('DIRECT')} disabled={busy} />
            <span>
              <span className="choice-title">{t('review.onlyStore', { store: draft.storeName })}</span>
              <span className="muted small">{t('review.onlyStoreHint')}</span>
            </span>
          </label>
        )}
      </div>

      <div className="location-bar">
        <span className="location-bar-text">
          <span aria-hidden="true">📍</span> {t(location.label)}
          <span className="muted small"> · {formatCoords(location)}</span>
        </span>
        <Link to="/c/location" state={{ returnTo: '/c/new/review' }} className="link-btn">
          {t('location.change')}
        </Link>
      </div>

      <WaitNote secondsLeft={limit.secondsLeft} />
      {errorBox}

      <div className="sticky-action">
        <button type="button" className="btn btn-block" onClick={() => send()} disabled={busy || limit.locked} aria-busy={busy}>
          {busy && <span className="spinner" aria-hidden="true" />}
          {busy ? t('review.sending') : t('review.send')}
        </button>
      </div>
    </section>
  );
}
