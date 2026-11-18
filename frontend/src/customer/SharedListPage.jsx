import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import {
  addSharedItem,
  deleteSharedItem,
  getSharedList,
  preparePlaceSharedList,
  unlockSharedList,
} from '../api/endpoints.js';
import { useAuth } from '../auth/AuthContext.jsx';
import BackButton from '../components/BackButton.jsx';
import ErrorBox from '../components/ErrorBox.jsx';
import Loader from '../components/Loader.jsx';
import { emptyDraft, newRow, saveDraft } from '../lib/draft.js';
import { formatQty } from '../lib/units.js';
import { getGuestKey, getGuestName, setGuestName, sharedListJoinUrl } from '../lib/guestId.js';
import { useT } from '../i18n/useT.js';
import { errorText } from '../components/ErrorBox.jsx';

const POLL_MS = 2500;
const UNITS = ['PCS', 'G', 'KG', 'ML', 'L', 'PACKET', 'DOZEN'];

export default function SharedListPage() {
  const { token } = useParams();
  const { t, tn } = useT();
  const { user } = useAuth();
  const navigate = useNavigate();
  const guestKey = useMemo(() => getGuestKey(), []);
  const [guestLabel, setGuestLabel] = useState(() => getGuestName() || (user?.name ? user.name.split(' ')[0] : ''));
  const [list, setList] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [shareNote, setShareNote] = useState('');
  const [draftItem, setDraftItem] = useState({ name: '', quantity: '', unit: 'PCS' });
  const versionRef = useRef(null);
  const nameRef = useRef(null);

  const refresh = useCallback(
    async (signal) => {
      try {
        const data = await getSharedList(token, guestKey, { signal });
        if (signal?.aborted) return;
        if (versionRef.current !== data.version || !list) {
          versionRef.current = data.version;
          setList(data);
        } else {
          // Still refresh timestamps/status even if version same
          setList(data);
        }
        setError(null);
      } catch (err) {
        if (!signal?.aborted) setError(err);
      }
    },
    [token, guestKey, list]
  );

  useEffect(() => {
    const controller = new AbortController();
    refresh(controller.signal);
    const timer = setInterval(() => refresh(), POLL_MS);
    return () => {
      controller.abort();
      clearInterval(timer);
    };
  }, [token, guestKey]); // eslint-disable-line react-hooks/exhaustive-deps -- poll independently of list

  async function onShare() {
    const url = sharedListJoinUrl(token);
    const text = t('group.shareText', { host: list?.ownerLabel || 'Host', url });
    try {
      if (navigator.share) {
        await navigator.share({ title: t('group.title'), text, url });
        setShareNote(t('group.shared'));
        return;
      }
    } catch {
      // fall through to clipboard
    }
    try {
      await navigator.clipboard.writeText(`${text}`);
      setShareNote(t('group.copied'));
    } catch {
      setShareNote(url);
    }
  }

  async function onAdd(e) {
    e.preventDefault();
    const name = draftItem.name.trim();
    if (!name) return;
    const qtyText = String(draftItem.quantity).trim().replace(',', '.');
    const quantity = qtyText === '' ? 1 : Number(qtyText);
    if (!Number.isFinite(quantity) || quantity <= 0) {
      setError({ code: 'VALIDATION_ERROR', message: t('newList.fixRows') });
      return;
    }
    if (!user && !guestLabel.trim()) {
      setError({ code: 'VALIDATION_ERROR', message: t('group.nameRequired') });
      return;
    }
    setBusy(true);
    setError(null);
    try {
      if (guestLabel.trim()) setGuestName(guestLabel.trim());
      const data = await addSharedItem(token, {
        name,
        quantity,
        unit: draftItem.unit,
        guestName: guestLabel.trim() || undefined,
        guestKey,
      });
      setList(data);
      versionRef.current = data.version;
      setDraftItem({ name: '', quantity: '', unit: draftItem.unit });
      nameRef.current?.focus();
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  async function onDelete(itemId) {
    setBusy(true);
    try {
      const data = await deleteSharedItem(token, itemId, guestKey);
      setList(data);
      versionRef.current = data.version;
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  async function onPreparePlace() {
    if (!user) {
      navigate('/login', { state: { from: `/join/${token}` } });
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const data = await preparePlaceSharedList(token);
      const rows = (data.items || []).map((it) => ({
        ...newRow(),
        name: it.name,
        quantity: formatQty(it.quantity),
        unit: it.unit,
      }));
      const draft = emptyDraft();
      saveDraft(user.id, {
        ...draft,
        items: rows.length ? rows : emptyDraft().items,
        mode: 'NEAREST',
        sharedToken: token,
      });
      setList(data);
      navigate('/c/new/review');
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  async function onUnlock() {
    setBusy(true);
    try {
      const data = await unlockSharedList(token);
      setList(data);
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  if (!list && !error) {
    return (
      <section className="screen">
        <Loader lines={5} />
      </section>
    );
  }

  if (error && !list) {
    return (
      <section className="screen">
        <BackButton fallback="/" />
        <ErrorBox error={error} onRetry={() => refresh()} />
      </section>
    );
  }

  const open = list.status === 'OPEN';
  const locked = list.status === 'LOCKED';
  const contributors = new Set((list.items || []).map((i) => i.addedByLabel)).size;

  return (
    <section className="screen">
      <BackButton fallback={user ? '/c/new' : '/'} />
      <h2>{list.title || t('group.title')}</h2>
      <p className="muted small">
        {t('group.subtitle', { host: list.ownerLabel, n: list.itemCount })}
        {contributors > 1 ? ` · ${t('group.people', { n: contributors })}` : ''}
      </p>

      {list.owner && (
        <div className="card group-share-card">
          <p className="label">{t('group.inviteTitle')}</p>
          <p className="muted small">{t('group.inviteHint')}</p>
          <button type="button" className="btn btn-secondary btn-block" onClick={onShare} disabled={busy}>
            {t('group.shareWhatsApp')}
          </button>
          {shareNote && <p className="muted small">{shareNote}</p>}
          <p className="muted small mono-link">{sharedListJoinUrl(token)}</p>
        </div>
      )}

      {!user && open && (
        <label className="field">
          <span className="label">{t('group.yourName')}</span>
          <input
            className="input"
            maxLength={40}
            value={guestLabel}
            onChange={(e) => setGuestLabel(e.target.value)}
            placeholder={t('group.namePlaceholder')}
          />
        </label>
      )}

      {locked && (
        <div className="card note" role="status">
          <p className="label">{t('group.lockedTitle')}</p>
          <p className="muted small">{t('group.lockedHint')}</p>
          {list.owner && (
            <button type="button" className="link-btn" onClick={onUnlock} disabled={busy}>
              {t('group.unlock')}
            </button>
          )}
        </div>
      )}

      {list.status === 'EXPIRED' && (
        <div className="card note" role="status">
          <p className="label">{t('group.expiredTitle')}</p>
          <p className="muted small">{t('group.expiredHint')}</p>
        </div>
      )}

      {list.status === 'PLACED' && (
        <div className="card note" role="status">
          <p className="label">{t('group.placedTitle')}</p>
          <p className="muted small">{t('group.placedHint')}</p>
          {list.owner && (
            <Link to="/c/orders" className="link-btn">
              {t('group.viewOrders')}
            </Link>
          )}
        </div>
      )}

      <ol className="item-list group-item-list">
        {(list.items || []).map((item, index) => (
          <li key={item.id} className="item-row group-item-row">
            <div className="item-row-top">
              <span className="target-name">
                {index + 1}. {item.name}
              </span>
              {item.mine && open && (
                <button
                  type="button"
                  className="icon-btn"
                  aria-label={t('newList.deleteItem', { n: index + 1 })}
                  onClick={() => onDelete(item.id)}
                  disabled={busy}
                >
                  <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
                    <path d="M6 6l12 12M18 6L6 18" />
                  </svg>
                </button>
              )}
            </div>
            <p className="muted small">
              {formatQty(item.quantity)} {t(`unit.${item.unit}`)} · {t('group.addedBy', { name: item.addedByLabel })}
            </p>
          </li>
        ))}
      </ol>

      {open && (
        <form className="group-add-form card" onSubmit={onAdd}>
          <p className="label">{t('group.addYours')}</p>
          <input
            ref={nameRef}
            className="input"
            placeholder={t('newList.namePlaceholder')}
            maxLength={60}
            value={draftItem.name}
            onChange={(e) => setDraftItem((d) => ({ ...d, name: e.target.value }))}
            disabled={busy}
          />
          <div className="item-row-bottom">
            <input
              className="input item-qty"
              inputMode="decimal"
              placeholder="1"
              maxLength={8}
              value={draftItem.quantity}
              onChange={(e) => setDraftItem((d) => ({ ...d, quantity: e.target.value }))}
              disabled={busy}
            />
            <select
              className="input item-unit"
              value={draftItem.unit}
              onChange={(e) => setDraftItem((d) => ({ ...d, unit: e.target.value }))}
              disabled={busy}
              aria-label={t('newList.unit')}
            >
              {UNITS.map((u) => (
                <option key={u} value={u}>
                  {t(`unit.${u}`)}
                </option>
              ))}
            </select>
          </div>
          <button type="submit" className="btn btn-secondary btn-block" disabled={busy || !draftItem.name.trim()}>
            + {t('group.addItem')}
          </button>
        </form>
      )}

      {error && (
        <p className="form-error" role="alert">
          {errorText(error, t)}
        </p>
      )}

      {list.owner && (open || locked) && (
        <div className="sticky-action">
          <button type="button" className="btn btn-block" onClick={onPreparePlace} disabled={busy || list.itemCount < 1}>
            {t('group.sendToShops')}
          </button>
          <p className="muted small">{tn('requests.items', list.itemCount)}</p>
        </div>
      )}

      {!list.owner && open && (
        <p className="muted small">{t('group.friendHint', { host: list.ownerLabel })}</p>
      )}
    </section>
  );
}
