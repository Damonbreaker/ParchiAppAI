import { Suspense, lazy, useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { createSharedList } from '../api/endpoints.js';
import { useAuth } from '../auth/AuthContext.jsx';
import BackButton from '../components/BackButton.jsx';
import ItemRow from '../components/ItemRow.jsx';
import { useHistoryOverlay } from '../lib/useHistoryOverlay.js';
import { MAX_ITEMS, cleanItems, emptyDraft, hasContent, loadDraft, newRow, saveDraft } from '../lib/draft.js';
import { clearScannedPhoto, getScannedPhoto } from '../lib/parchiPhoto.js';
import { useT } from '../i18n/useT.js';
import { errorText } from '../components/ErrorBox.jsx';

const SAVE_DELAY_MS = 400;
// Only needed after a scan, and only when the photo is tapped.
const PhotoViewer = lazy(() => import('../components/PhotoViewer.jsx'));

export default function NewRequest() {
  const { t } = useT();
  const { user } = useAuth();
  const navigate = useNavigate();

  const [initial] = useState(() => loadDraft(user.id) || emptyDraft());
  const [rows, setRows] = useState(() => (initial.items.length ? initial.items : emptyDraft().items));
  const [target, setTarget] = useState(() => ({
    mode: initial.mode === 'DIRECT' && initial.storeId ? 'DIRECT' : 'NEAREST',
    storeId: initial.storeId || null,
    storeName: initial.storeName || null,
  }));
  // Some rows came from a parchi scan; sent as fromPhoto with the request.
  const [fromPhoto] = useState(() => Boolean(initial.fromPhoto));
  // The scanned photo, only in memory and only while this screen is open, so the
  // customer can compare. Gone after a reload or after leaving the editor.
  const [photoBlob] = useState(getScannedPhoto);
  const [photoUrl, setPhotoUrl] = useState(null);
  const [viewerOpen, showViewer, hideViewer] = useHistoryOverlay('photo');

  useEffect(() => {
    clearScannedPhoto();
    if (!photoBlob) return undefined;
    const url = URL.createObjectURL(photoBlob);
    setPhotoUrl(url);
    return () => {
      URL.revokeObjectURL(url);
      setPhotoUrl(null);
    };
  }, [photoBlob]);

  const [rowErrors, setRowErrors] = useState({});
  const [formError, setFormError] = useState(null);
  const [saved, setSaved] = useState(false);
  const [sharing, setSharing] = useState(false);

  const nameInputs = useRef(new Map());
  const focusRowId = useRef(null);
  const latest = useRef({ rows, target, fromPhoto });
  const dirty = useRef(false);
  latest.current = { rows, target, fromPhoto };

  const persist = useCallback(() => {
    const { rows: items, target: tg, fromPhoto: photo } = latest.current;
    saveDraft(user.id, { items, ...tg, ...(photo ? { fromPhoto: true } : {}) });
    dirty.current = false;
    setSaved(true);
  }, [user.id]);

  // Debounced auto-save after edits; skips the first render (nothing changed yet).
  const firstRender = useRef(true);
  useEffect(() => {
    if (firstRender.current) {
      firstRender.current = false;
      return;
    }
    dirty.current = true;
    setSaved(false);
    const timer = setTimeout(persist, SAVE_DELAY_MS);
    return () => clearTimeout(timer);
  }, [rows, target, persist]);

  // Save whatever is pending if the user leaves before the timer fires.
  useEffect(() => () => dirty.current && persist(), [persist]);

  // Focus a newly added row once it is in the DOM.
  useEffect(() => {
    const id = focusRowId.current;
    if (!id) return;
    focusRowId.current = null;
    nameInputs.current.get(id)?.focus();
  }, [rows]);

  const registerName = useCallback((id, el) => {
    if (el) nameInputs.current.set(id, el);
    else nameInputs.current.delete(id);
  }, []);

  const onChange = useCallback((id, field, value) => {
    // Editing a row the photo reader wasn't sure about clears its amber mark.
    setRows((rs) =>
      rs.map((r) => {
        if (r.id !== id) return r;
        const { unsure, ...rest } = r;
        return { ...rest, [field]: value };
      })
    );
    setRowErrors((errs) => {
      if (!errs[id]) return errs;
      const next = { ...errs };
      delete next[id];
      return next;
    });
    setFormError(null);
  }, []);

  const onRowUpdate = useCallback((id, nextRow) => {
    setRows((rs) => rs.map((r) => (r.id === id ? { ...nextRow, unsure: undefined } : r)));
    setFormError(null);
  }, []);

  const onDelete = useCallback((id) => {
    setRows((rs) => {
      const next = rs.filter((r) => r.id !== id);
      return next.length ? next : [newRow()];
    });
  }, []);

  // The row is made outside the updater so StrictMode's double call can't
  // focus a row id that was thrown away.
  const addRow = useCallback(() => {
    if (latest.current.rows.length >= MAX_ITEMS) return;
    const row = newRow();
    focusRowId.current = row.id;
    setRows((rs) => (rs.length >= MAX_ITEMS ? rs : [...rs, row]));
  }, []);

  // Enter in quantity -> next row's name, adding a row at the end.
  const onQtyEnter = useCallback(
    (id) => {
      const list = latest.current.rows;
      const index = list.findIndex((r) => r.id === id);
      const next = list[index + 1];
      if (next) nameInputs.current.get(next.id)?.focus();
      else addRow();
    },
    [addRow]
  );

  function onReview() {
    const { items, errors } = cleanItems(rows);
    setRowErrors(errors);
    if (Object.keys(errors).length) {
      setFormError('newList.fixRows');
      return;
    }
    if (items.length === 0) {
      setFormError('newList.noItems');
      return;
    }
    persist();
    navigate('/c/new/review');
  }

  async function onShareGroup() {
    const { items, errors } = cleanItems(rows);
    setRowErrors(errors);
    if (Object.keys(errors).length) {
      setFormError('newList.fixRows');
      return;
    }
    persist();
    setSharing(true);
    setFormError(null);
    try {
      const created = await createSharedList({
        title: t('group.defaultTitle', { name: user.name?.split(' ')[0] || 'Host' }),
        items: items.length ? items : undefined,
      });
      navigate(`/c/shared/${created.token}`);
    } catch (err) {
      setFormError(errorText(err, t));
    } finally {
      setSharing(false);
    }
  }

  const atLimit = rows.length >= MAX_ITEMS;

  return (
    <section className="screen">
      <BackButton />
      <h2>{t('newList.title')}</h2>

      {target.mode === 'DIRECT' && (
        <div className="target-banner">
          <span>{t('newList.sendingTo', { store: target.storeName })}</span>
          <button type="button" className="link-btn" onClick={() => setTarget((tg) => ({ ...tg, mode: 'NEAREST' }))}>
            {t('newList.sendNearestInstead')}
          </button>
        </div>
      )}

      {photoUrl && (
        <button type="button" className="card photo-row" onClick={showViewer} aria-label={t('photo.open')}>
          <img src={photoUrl} alt="" className="photo-thumb" />
          <span className="photo-row-text">
            <span className="target-name">{t('photo.yours')}</span>
            <span className="muted small">{t('photo.tapToCompare')}</span>
          </span>
        </button>
      )}
      {fromPhoto && (
        <div className="card note" role="note">
          <p className="label">{t('scan.checkBanner')}</p>
        </div>
      )}

      <ol className="item-list">
        {rows.map((row, index) => (
          <ItemRow
            key={row.id}
            row={row}
            index={index}
            error={rowErrors[row.id]}
            onChange={onChange}
            onDelete={onDelete}
            onQtyEnter={onQtyEnter}
            registerName={registerName}
            onRowUpdate={onRowUpdate}
          />
        ))}
      </ol>

      {atLimit ? (
        <p className="muted small">{t('newList.limit', { n: MAX_ITEMS })}</p>
      ) : (
        <button type="button" className="btn btn-secondary btn-block" onClick={addRow}>
          + {t('newList.addItem')}
        </button>
      )}

      <p className="muted small draft-hint" aria-live="polite">
        {saved && hasContent(rows) ? `✓ ${t('newList.draftSaved')}` : ' '}
      </p>

      {formError && (
        <p className="form-error" role="alert">
          {formError.includes(' ') ? formError : t(formError)}
        </p>
      )}

      <div className="sticky-action">
        <button type="button" className="btn btn-block" onClick={onReview}>
          {t('newList.review')}
        </button>
        <button
          type="button"
          className="btn btn-secondary btn-block"
          onClick={onShareGroup}
          disabled={sharing}
          aria-busy={sharing}
        >
          {sharing ? t('group.creating') : t('group.inviteFriends')}
        </button>
        <p className="muted small">{t('group.inviteFromEditorHint')}</p>
      </div>
      {viewerOpen && photoUrl && (
        <Suspense fallback={null}>
          <PhotoViewer title={t('photo.yours')} src={photoUrl} onClose={hideViewer} />
        </Suspense>
      )}
    </section>
  );
}
