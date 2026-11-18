import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { createPool, joinCommunity, listCommunities } from '../api/endpoints.js';
import BackButton from '../components/BackButton.jsx';
import ErrorBox, { errorText } from '../components/ErrorBox.jsx';
import Loader from '../components/Loader.jsx';
import { useT } from '../i18n/useT.js';

export default function CommunityPage() {
  const { t } = useT();
  const navigate = useNavigate();
  const [state, setState] = useState({ status: 'loading' });
  const [busyId, setBusyId] = useState(null);
  const [error, setError] = useState(null);

  const load = useCallback((signal) => {
    setState({ status: 'loading' });
    listCommunities({ signal })
      .then((items) => setState({ status: 'done', items: items || [] }))
      .catch((err) => {
        if (!signal?.aborted) setState({ status: 'error', error: err });
      });
  }, []);

  useEffect(() => {
    const c = new AbortController();
    load(c.signal);
    return () => c.abort();
  }, [load]);

  async function onJoin(communityId) {
    setBusyId(communityId);
    setError(null);
    try {
      await joinCommunity(communityId);
      load();
    } catch (err) {
      setError(err);
    } finally {
      setBusyId(null);
    }
  }

  async function onStartPool(communityId) {
    setBusyId(communityId);
    setError(null);
    try {
      const joined = await joinCommunity(communityId);
      const pool = await createPool({ communityId: joined.id, windowMinutes: 12 });
      navigate(`/c/pool/${pool.token}`);
    } catch (err) {
      setError(err);
    } finally {
      setBusyId(null);
    }
  }

  if (state.status === 'loading') {
    return (
      <section className="screen">
        <Loader lines={5} />
      </section>
    );
  }
  if (state.status === 'error') {
    return (
      <section className="screen">
        <BackButton fallback="/c" />
        <ErrorBox error={state.error} onRetry={() => load()} />
      </section>
    );
  }

  return (
    <section className="screen">
      <BackButton fallback="/c" />
      <h2>{t('pool.communitiesTitle')}</h2>
      <p className="muted small">{t('pool.communitiesHint')}</p>

      <div className="store-list">
        {state.items.map((c) => (
          <div key={c.id} className="card">
            <p className="target-name">{c.label}</p>
            <p className="muted small">
              {c.societyName} · {c.towerName}
              {c.member ? ` · ${t('pool.yourAlias', { alias: c.myAlias })}` : ''}
            </p>
            <div className="quote-card-actions" style={{ gap: '0.5rem', marginTop: '0.5rem' }}>
              {!c.member ? (
                <button
                  type="button"
                  className="btn btn-secondary btn-block"
                  disabled={busyId === c.id}
                  onClick={() => onJoin(c.id)}
                >
                  {t('pool.joinCommunity')}
                </button>
              ) : (
                <button
                  type="button"
                  className="btn btn-block"
                  disabled={busyId === c.id}
                  onClick={() => onStartPool(c.id)}
                >
                  {t('pool.startPool')}
                </button>
              )}
            </div>
          </div>
        ))}
      </div>

      {error && (
        <p className="form-error" role="alert">
          {errorText(error, t)}
        </p>
      )}
      <p className="muted small">
        <Link to="/c/new">{t('pool.orGroupList')}</Link>
      </p>
    </section>
  );
}
