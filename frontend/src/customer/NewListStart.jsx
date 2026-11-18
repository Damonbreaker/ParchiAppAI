import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext.jsx';
import BackButton from '../components/BackButton.jsx';
import PhotoPicker from './PhotoPicker.jsx';
import { loadDraft } from '../lib/draft.js';
import { setPickedFile } from '../lib/parchiPhoto.js';
import { useT } from '../i18n/useT.js';

// /c/new: how to make the list. Photo -> /c/new/scan, typing -> /c/new/edit.
export default function NewListStart() {
  const { t, tn } = useT();
  const { user } = useAuth();
  const navigate = useNavigate();
  const [draft] = useState(() => loadDraft(user.id));
  // Fetch the scan screen's code now, while the customer is choosing, so a picked
  // photo starts processing at once even on slow data.
  useEffect(() => {
    import('./ScanParchi.jsx').catch(() => {});
  }, []);
  const count = draft ? draft.items.filter((r) => r.name.trim()).length : 0;

  function onPick(file) {
    setPickedFile(file);
    navigate('/c/new/scan', { state: { fromStart: true } });
  }

  return (
    <section className="screen">
      <BackButton fallback="/c" />
      <h2>{t('start.title')}</h2>
      <p className="muted small">{t('start.heroHint')}</p>

      {draft?.mode === 'DIRECT' && draft.storeName && (
        <div className="target-banner">
          <span>{t('newList.sendingTo', { store: draft.storeName })}</span>
        </div>
      )}

      {count > 0 && (
        <Link to="/c/new/edit" className="card continue-card">
          <span className="target-name">{t('start.continue')}</span>
          <span className="muted small">{tn('requests.items', count)} →</span>
        </Link>
      )}

      <div className="start-options">
        <PhotoPicker onPick={onPick} />
        <Link to="/c/new/edit" className="start-option">
          <span className="start-icon" aria-hidden="true">✍️</span>
          <span className="start-text">
            <span className="start-title">{t('start.type')}</span>
            <span className="muted small">{t('start.typeHint')}</span>
          </span>
        </Link>
        <Link to="/c/new/edit" state={{ startGroup: true }} className="start-option">
          <span className="start-icon" aria-hidden="true">👥</span>
          <span className="start-text">
            <span className="start-title">{t('start.group')}</span>
            <span className="muted small">{t('start.groupHint')}</span>
          </span>
        </Link>
      </div>
      {count > 0 && <p className="muted small">{t('start.addsToList')}</p>}
    </section>
  );
}
