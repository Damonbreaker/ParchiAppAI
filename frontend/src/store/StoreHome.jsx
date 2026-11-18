import { useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext.jsx';
import { useStore } from './StoreContext.jsx';
import { topUpParchi, updateMyStore } from '../api/endpoints.js';
import { errorText } from '../components/ErrorBox.jsx';
import { mapsUrl } from '../lib/geo.js';
import { useT } from '../i18n/useT.js';

function formatParchi(n) {
  const v = Number(n);
  if (!Number.isFinite(v)) return '0';
  return Number.isInteger(v) ? String(v) : v.toFixed(2);
}

export default function StoreHome() {
  const { t } = useT();
  const { user } = useAuth();
  const { store, setStore } = useStore();
  const [busy, setBusy] = useState(false);
  const [topBusy, setTopBusy] = useState(false);
  const [error, setError] = useState(null);
  const busyRef = useRef(false);

  // Flip right away, revert if the server says no.
  async function toggleOpen() {
    if (busyRef.current) return;
    busyRef.current = true;
    const before = store;
    const next = !store.isOpen;
    setStore({ ...store, isOpen: next });
    setBusy(true);
    setError(null);
    try {
      const saved = await updateMyStore({ isOpen: next });
      setStore(saved && saved.id ? saved : { ...before, isOpen: next });
    } catch (err) {
      setStore(before);
      setError(err);
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  }

  async function topUp(amount) {
    if (topBusy) return;
    setTopBusy(true);
    setError(null);
    try {
      setStore(await topUpParchi(amount));
    } catch (err) {
      setError(err);
    } finally {
      setTopBusy(false);
    }
  }

  const balance = store.parchiBalance != null ? Number(store.parchiBalance) : 100;
  const low = store.lowParchi || balance <= 5;

  return (
    <section className="screen">
      <div className="welcome-banner card">
        <p className="welcome-title">{t('store.welcome', { name: user?.name || store.name })}</p>
        <p className="muted small">{t('store.welcomeHint')}</p>
      </div>

      <h2>{store.name}</h2>
      <p className="muted">{store.area}</p>

      <div className={`card parchi-wallet${low ? ' is-low' : ''}`}>
        <p className="label">{t('store.parchiWallet')}</p>
        <p className="parchi-balance">
          {formatParchi(balance)} <span className="muted small">{t('store.parchiUnit')}</span>
        </p>
        <p className="muted small">{t('store.parchiHint')}</p>
        {low && (
          <div className="parchi-low-note" role="status">
            <p className="label">{t('store.parchiLowTitle')}</p>
            <p className="small">{t('store.parchiLowBody')}</p>
            <button
              type="button"
              className="btn btn-block"
              onClick={() => topUp(100)}
              disabled={topBusy}
              aria-busy={topBusy}
            >
              {topBusy ? t('common.wait') : t('store.parchiTopUp100')}
            </button>
          </div>
        )}
        {!low && (
          <button
            type="button"
            className="btn btn-secondary btn-block"
            onClick={() => topUp(50)}
            disabled={topBusy}
          >
            {t('store.parchiTopUp50')}
          </button>
        )}
      </div>

      <button
        type="button"
        role="switch"
        aria-checked={store.isOpen}
        aria-busy={busy}
        className={store.isOpen ? 'open-toggle is-open' : 'open-toggle'}
        onClick={toggleOpen}
        disabled={busy}
      >
        <span className="open-toggle-track" aria-hidden="true">
          <span className="open-toggle-thumb">{busy && <span className="spinner" />}</span>
        </span>
        <span className="open-toggle-text">
          <span className="open-toggle-title">{t(store.isOpen ? 'store.isOpen' : 'store.isClosed')}</span>
          <span className="small">{t(store.isOpen ? 'store.openHint' : 'store.closedHint')}</span>
        </span>
      </button>
      {error && (
        <p className="form-error" role="alert">
          {errorText(error, t)}
        </p>
      )}

      <dl className="card info-list">
        <div>
          <dt>{t('field.address')}</dt>
          <dd>{store.address}</dd>
        </div>
        <div>
          <dt>{t('field.area')}</dt>
          <dd>{store.area}</dd>
        </div>
        {store.phone && (
          <div>
            <dt>{t('field.storePhone')}</dt>
            <dd>{store.phone}</dd>
          </div>
        )}
        <div>
          <dt>{t('field.location')}</dt>
          <dd>
            <a href={mapsUrl(store)} target="_blank" rel="noopener noreferrer">
              {t('geo.checkMaps')}
            </a>
          </dd>
        </div>
      </dl>

      <Link to="/s/store/edit" className="btn btn-secondary btn-block">
        {t('store.edit')}
      </Link>
    </section>
  );
}
