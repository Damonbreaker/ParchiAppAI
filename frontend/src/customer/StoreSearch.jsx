import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { searchStores } from '../api/endpoints.js';
import BackButton from '../components/BackButton.jsx';
import ErrorBox from '../components/ErrorBox.jsx';
import StoreCard, { StoreCardSkeleton } from '../components/StoreCard.jsx';
import { ensureDemoLocation, getSavedLocation, isDemoBuild } from '../lib/geo.js';
import { useT } from '../i18n/useT.js';

const MIN_CHARS = 2;
const DEBOUNCE_MS = 300;

export default function StoreSearch() {
  const { t } = useT();
  // The query lives in the URL so coming back from a store page keeps the results.
  const [params, setParams] = useSearchParams();
  const q = params.get('q') || '';
  const [location] = useState(() => (isDemoBuild() ? ensureDemoLocation() : getSavedLocation()));
  const [state, setState] = useState({ status: 'idle' });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    const term = q.trim();
    if (term.length < MIN_CHARS) {
      setState({ status: 'idle' });
      return;
    }
    // One controller per keystroke: the cleanup cancels both the pending
    // timer and any request still in flight from the previous term.
    const controller = new AbortController();
    const timer = setTimeout(() => {
      setState({ status: 'loading' });
      searchStores({ q: term, lat: location?.lat, lng: location?.lng }, { signal: controller.signal })
        .then((stores) => setState({ status: 'done', stores: stores || [], term }))
        .catch((error) => {
          if (!controller.signal.aborted) setState({ status: 'error', error });
        });
    }, DEBOUNCE_MS);
    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, [q, location, attempt]);

  let body;
  if (state.status === 'idle') {
    body = (
      <div className="card empty">
        <p className="muted">{t('search.hint')}</p>
      </div>
    );
  } else if (state.status === 'loading') {
    body = <StoreCardSkeleton />;
  } else if (state.status === 'error') {
    body = <ErrorBox error={state.error} onRetry={() => setAttempt((n) => n + 1)} />;
  } else if (state.stores.length === 0) {
    body = (
      <div className="card empty">
        <p className="muted">{t('search.noResults', { q: state.term })}</p>
      </div>
    );
  } else {
    body = (
      <div className="store-list">
        {state.stores.map((store) => (
          <StoreCard key={store.id} store={store} />
        ))}
      </div>
    );
  }

  return (
    <section className="screen">
      <BackButton />
      <h2>{t('search.title')}</h2>
      <input
        type="search"
        className="input"
        enterKeyHint="search"
        autoFocus
        aria-label={t('search.title')}
        placeholder={t('home.searchPlaceholder')}
        value={q}
        onChange={(e) => setParams(e.target.value ? { q: e.target.value } : {}, { replace: true })}
      />
      {body}
    </section>
  );
}
