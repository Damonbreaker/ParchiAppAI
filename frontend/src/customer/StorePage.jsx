import { useCallback, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext.jsx';
import { getStore } from '../api/endpoints.js';
import { setDraftStore } from '../lib/draft.js';
import BackButton from '../components/BackButton.jsx';
import ErrorBox from '../components/ErrorBox.jsx';
import Loader from '../components/Loader.jsx';
import { OpenBadge } from '../components/StoreCard.jsx';
import { ensureDemoLocation, formatKm, getSavedLocation, isDemoBuild, mapsUrl } from '../lib/geo.js';
import { useT } from '../i18n/useT.js';

export default function StorePage() {
  const { t } = useT();
  const { id } = useParams();
  const [location] = useState(() => (isDemoBuild() ? ensureDemoLocation() : getSavedLocation()));
  const [state, setState] = useState({ status: 'loading' });
  const { user } = useAuth();
  const navigate = useNavigate();

  // Keeps any draft items and points the list at this store.
  function startList(store) {
    setDraftStore(user.id, store);
    navigate('/c/new');
  }

  const load = useCallback(
    (signal) => {
      setState({ status: 'loading' });
      getStore(id, { lat: location?.lat, lng: location?.lng }, { signal })
        .then((store) => setState({ status: 'done', store }))
        .catch((error) => {
          if (!signal?.aborted) setState({ status: 'error', error });
        });
    },
    [id, location]
  );

  useEffect(() => {
    const controller = new AbortController();
    load(controller.signal);
    return () => controller.abort();
  }, [load]);

  let body;
  if (state.status === 'loading') {
    body = <Loader lines={4} />;
  } else if (state.status === 'error') {
    // Retrying a missing store won't help.
    const notFound = state.error.status === 404;
    body = <ErrorBox error={state.error} onRetry={notFound ? undefined : () => load()} />;
  } else {
    const { store } = state;
    body = (
      <>
        <div className="store-page-head">
          <h2>{store.name}</h2>
          <OpenBadge isOpen={store.isOpen} />
        </div>
        <dl className="card info-list">
          <div>
            <dt>{t('field.area')}</dt>
            <dd>{store.area}</dd>
          </div>
          <div>
            <dt>{t('field.address')}</dt>
            <dd>{store.address}</dd>
          </div>
          {store.distanceKm != null && (
            <div>
              <dt>{t('storePage.distance')}</dt>
              <dd>{t('storePage.away', { distance: t('unit.km', { km: formatKm(store.distanceKm) }) })}</dd>
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
        {store.isOpen ? (
          <button type="button" className="btn btn-block btn-tall" onClick={() => startList(store)}>
            {t('storePage.send')}
          </button>
        ) : (
          <button type="button" className="btn btn-block btn-tall" disabled>
            <span className="btn-stack">
              <span>{t('storePage.send')}</span>
              <span className="small">{t('storePage.closedNow')}</span>
            </span>
          </button>
        )}
      </>
    );
  }

  return (
    <section className="screen">
      <BackButton />
      {body}
    </section>
  );
}
