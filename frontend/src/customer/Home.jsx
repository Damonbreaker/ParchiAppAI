import { useCallback, useEffect, useState } from 'react';
import { Link, Navigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext.jsx';
import { getNearbyStores } from '../api/endpoints.js';
import ErrorBox from '../components/ErrorBox.jsx';
import StoreCard, { StoreCardSkeleton } from '../components/StoreCard.jsx';
import { ensureDemoLocation, formatCoords, getSavedLocation, isDemoBuild } from '../lib/geo.js';
import { useT } from '../i18n/useT.js';

function NearbyStores({ location }) {
  const { t } = useT();
  const [state, setState] = useState({ status: 'loading' });

  const load = useCallback(
    (signal) => {
      setState({ status: 'loading' });
      getNearbyStores(location, { signal })
        .then((stores) => setState({ status: 'done', stores: stores || [] }))
        .catch((error) => {
          if (!signal?.aborted) setState({ status: 'error', error });
        });
    },
    [location]
  );

  useEffect(() => {
    const controller = new AbortController();
    load(controller.signal);
    return () => controller.abort();
  }, [load]);

  if (state.status === 'loading') return <StoreCardSkeleton />;
  if (state.status === 'error') return <ErrorBox error={state.error} onRetry={() => load()} />;
  if (state.stores.length === 0) {
    return (
      <div className="card empty">
        <p>{t('home.nearbyEmpty')}</p>
        <p className="muted small">{t('home.nearbyEmptyHint')}</p>
      </div>
    );
  }
  return (
    <div className="store-list">
      {state.stores.map((store) => (
        <StoreCard key={store.id} store={store} />
      ))}
    </div>
  );
}

export default function Home() {
  const { t } = useT();
  const { user } = useAuth();
  // Demo builds auto-pick the Noida test point so seeded shopkeepers receive lists.
  const [location] = useState(() => (isDemoBuild() ? ensureDemoLocation() : getSavedLocation()));

  if (!location) return <Navigate to="/c/location" replace />;

  return (
    <section className="screen">
      <div className="location-bar">
        <span className="location-bar-text">
          <span aria-hidden="true">📍</span> {t(location.label)}
          <span className="muted small"> · {formatCoords(location)}</span>
        </span>
        <Link to="/c/location" className="link-btn">
          {t('location.change')}
        </Link>
      </div>

      <Link to="/c/search" className="search-bar">
        <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
          <path d="M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14zM21 21l-5-5" />
        </svg>
        <span>{t('home.searchPlaceholder')}</span>
      </Link>

      <div className="welcome-banner card">
        <p className="welcome-title">{t('home.welcome', { name: user.name })}</p>
        <p className="muted small">{t('home.welcomeHint')}</p>
      </div>
      <Link to="/c/new" className="btn btn-block btn-tall">
        📷 {t('home.photoList')}
      </Link>
      <p className="muted small" style={{ textAlign: 'center', marginTop: '-0.35rem' }}>
        {t('home.photoListHint')}
      </p>
      <Link to="/c/new" className="link-btn" style={{ display: 'block', textAlign: 'center', marginBottom: '0.35rem' }}>
        ✍️ {t('home.writeList')}
      </Link>
      <Link to="/c/communities" className="link-btn" style={{ display: 'block', textAlign: 'center', marginBottom: '0.75rem' }}>
        🏢 {t('home.towerPool')}
      </Link>
      <h3>{t('home.nearby')}</h3>
      <NearbyStores location={location} />
    </section>
  );
}
