import { useCallback, useEffect, useRef, useState } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext.jsx';
import { scanParchi } from '../api/endpoints.js';
import BackButton from '../components/BackButton.jsx';
import { errorText } from '../components/ErrorBox.jsx';
import WaitNote from '../components/WaitNote.jsx';
import PhotoPicker from './PhotoPicker.jsx';
import { useRateLimit } from '../lib/useRateLimit.js';
import { compressImage } from '../lib/compressImage.js';
import { replaceWithScannedItems } from '../lib/draft.js';
import { clearPickedFile, getPickedFile, setPickedFile, setScannedPhoto } from '../lib/parchiPhoto.js';
import { setShareSnapshot } from '../lib/shareSnapshot.js';
import FactTicker from '../components/FactTicker.jsx';
import { useT } from '../i18n/useT.js';

// /c/new/scan: photo → scan → editor. Customer only sees the image and a simple reading state.
// phase: reading | error | empty
export default function ScanParchi() {
  const { t } = useT();
  const { user } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [file, setFile] = useState(getPickedFile);
  const [photo, setPhoto] = useState(null); // { blob, url }
  const [phase, setPhase] = useState('reading');
  const [error, setError] = useState(null); // { kind: 'unsupported' | 'tooLarge' | 'other', err? }
  const uploadRef = useRef(null);
  const limit = useRateLimit('scan');

  const upload = useCallback(
    async (compressed) => {
      uploadRef.current?.abort();
      const controller = new AbortController();
      uploadRef.current = controller;
      setError(null);
      setPhase('reading');
      try {
        const result = await scanParchi(compressed.blob, {
          signal: controller.signal,
          timeout: 180000,
        });
        const items = (result && result.items) || [];
        if (!items.length) {
          setPhase('empty');
          return;
        }
        replaceWithScannedItems(user.id, items);
        setScannedPhoto(compressed.blob);
        setShareSnapshot({
          photoBlob: compressed.blob,
          items: items.map((i) => i.name || i.text || '').filter(Boolean),
        });
        clearPickedFile();
        navigate('/c/new/edit', { replace: true, state: { fromScan: true } });
      } catch (err) {
        if (err.name === 'AbortError') return;
        if (limit.check(err)) setError({ kind: 'tooMany' });
        else if (err.code === 'UNSUPPORTED_IMAGE' || err.status === 415) setError({ kind: 'unsupported' });
        else if (err.code === 'IMAGE_TOO_LARGE' || err.status === 413) setError({ kind: 'tooLarge' });
        else setError({ kind: 'other', err });
        setPhase('error');
      }
    },
    [navigate, user.id, limit.check]
  );

  // New file -> compress once, then scan. Retry reuses `photo` (no recompress).
  useEffect(() => {
    if (!file) return undefined;
    let cancelled = false;
    let url = null;
    setPhoto(null);
    setPhase('reading');
    setError(null);
    compressImage(file)
      .then((compressed) => {
        if (cancelled) return;
        url = URL.createObjectURL(compressed.blob);
        const next = { ...compressed, url };
        setPhoto(next);
        upload(next);
      })
      .catch(() => {
        if (cancelled) return;
        setError({ kind: 'unsupported' });
        setPhase('error');
      });
    return () => {
      cancelled = true;
      uploadRef.current?.abort();
      if (url) URL.revokeObjectURL(url);
    };
  }, [file, upload]);

  // Leaving the screen drops the picked file; the draft keeps only the items.
  useEffect(() => () => clearPickedFile(), []);

  if (!file) return <Navigate to="/c/new" replace />;

  function leave(to) {
    uploadRef.current?.abort();
    if (to) navigate(to, { replace: true });
    else if (location.state?.fromStart) navigate(-1);
    else navigate('/c/new', { replace: true });
  }

  function pickAnother(next) {
    setPickedFile(next);
    setFile(next);
  }

  let message;
  if (error?.kind === 'unsupported') message = t('scan.err.unsupported');
  else if (error?.kind === 'tooLarge') message = t('scan.err.tooLarge');
  else if (error) message = errorText(error.err, t);

  return (
    <section className="screen">
      <BackButton fallback="/c/new" />
      <h2>{t('scan.title')}</h2>

      <div className="scan-preview">
        {photo ? (
          <img src={photo.url} alt={t('scan.previewAlt')} />
        ) : (
          <span className="scan-preview-empty" aria-hidden="true">📷</span>
        )}
      </div>

      {phase === 'reading' && (
        <div className="card scan-status" aria-live="polite">
          <p className="label scan-reading">
            <span className="spinner" aria-hidden="true" />
            {t('scan.reading')}
          </p>
          <FactTicker />
          <button type="button" className="btn btn-secondary btn-block" onClick={() => leave()}>
            {t('common.cancel')}
          </button>
        </div>
      )}

      {phase === 'error' && (
        <div className="card scan-status" role="alert">
          {error.kind === 'tooMany' ? <WaitNote secondsLeft={limit.secondsLeft} /> : <p className="form-error">{message}</p>}
          {(error.kind === 'other' || error.kind === 'tooMany') && photo && (
            <button type="button" className="btn btn-block" onClick={() => upload(photo)} disabled={limit.locked}>
              {t('common.retry')}
            </button>
          )}
          <p className="label">{t('scan.another')}</p>
          <PhotoPicker onPick={pickAnother} compact />
          <button type="button" className="btn btn-secondary btn-block" onClick={() => leave('/c/new/edit')}>
            {t('scan.typeInstead')}
          </button>
        </div>
      )}

      {phase === 'empty' && (
        <div className="card scan-status" role="status">
          <p className="label">{t('scan.emptyTitle')}</p>
          <p className="muted small">{t('scan.emptyHint')}</p>
          <p className="label">{t('scan.another')}</p>
          <PhotoPicker onPick={pickAnother} compact />
          <button type="button" className="btn btn-secondary btn-block" onClick={() => leave('/c/new/edit')}>
            {t('scan.typeInstead')}
          </button>
        </div>
      )}
    </section>
  );
}
