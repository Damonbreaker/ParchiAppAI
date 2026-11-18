import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import {
  approvePoolBag,
  closePool,
  getPool,
  joinPool,
  sendPool,
  setPoolMyList,
} from '../api/endpoints.js';
import BackButton from '../components/BackButton.jsx';
import ErrorBox, { errorText } from '../components/ErrorBox.jsx';
import Loader from '../components/Loader.jsx';
import { cleanItems, loadDraft, newRow } from '../lib/draft.js';
import { formatQty } from '../lib/units.js';
import { formatCountdown, poolJoinUrl } from '../lib/poolLink.js';
import { useAuth } from '../auth/AuthContext.jsx';
import { useT } from '../i18n/useT.js';

const POLL_MS = 3000;
const UNITS = ['PCS', 'G', 'KG', 'ML', 'L', 'PACKET', 'DOZEN'];

export default function PoolPage() {
  const { token } = useParams();
  const { t, tn } = useT();
  const { user } = useAuth();
  const navigate = useNavigate();
  const [pool, setPool] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [shareNote, setShareNote] = useState('');
  const [tick, setTick] = useState(0);
  const [draftItem, setDraftItem] = useState({ name: '', quantity: '', unit: 'PCS' });
  const [localItems, setLocalItems] = useState([]);

  const refresh = useCallback(
    async (signal) => {
      try {
        let data = await getPool(token, { signal });
        if (signal?.aborted) return;
        if (data && !data.member && data.status === 'OPEN') {
          data = await joinPool(token);
        }
        if (!signal?.aborted) {
          setPool(data);
          setError(null);
        }
      } catch (err) {
        if (!signal?.aborted) setError(err);
      }
    },
    [token]
  );

  useEffect(() => {
    const c = new AbortController();
    refresh(c.signal);
    const poll = setInterval(() => refresh(), POLL_MS);
    const clock = setInterval(() => setTick((x) => x + 1), 1000);
    return () => {
      c.abort();
      clearInterval(poll);
      clearInterval(clock);
    };
  }, [refresh]);

  const secondsLeft = useMemo(() => {
    if (!pool || pool.status !== 'OPEN') return 0;
    void tick;
    const end = Date.parse(pool.closesAt);
    if (!Number.isFinite(end)) return pool.secondsLeft || 0;
    return Math.max(0, Math.floor((end - Date.now()) / 1000));
  }, [pool, tick]);

  const myBag = pool?.bags?.find((b) => b.mine);

  async function onShare() {
    const url = poolJoinUrl(token);
    const text = t('pool.shareText', { tower: pool.communityLabel, url });
    try {
      if (navigator.share) {
        await navigator.share({ title: t('pool.title'), text, url });
        setShareNote(t('pool.shared'));
        return;
      }
    } catch {
      // clipboard
    }
    try {
      await navigator.clipboard.writeText(text);
      setShareNote(t('pool.copied'));
    } catch {
      setShareNote(url);
    }
  }

  function addLocalRow(e) {
    e.preventDefault();
    const name = draftItem.name.trim();
    if (!name) return;
    const qtyText = String(draftItem.quantity).trim().replace(',', '.');
    const quantity = qtyText === '' ? 1 : Number(qtyText);
    if (!Number.isFinite(quantity) || quantity <= 0) return;
    setLocalItems((rows) => [
      ...rows,
      { id: newRow().id, name, quantity: formatQty(quantity), unit: draftItem.unit },
    ]);
    setDraftItem({ name: '', quantity: '', unit: draftItem.unit });
  }

  async function onSubmitList() {
    const source = localItems.length
      ? localItems
      : (loadDraft(user.id)?.items || []).filter((r) => r.name?.trim());
    const { items, errors } = cleanItems(source);
    if (Object.keys(errors).length || !items.length) {
      setError({ code: 'VALIDATION_ERROR', message: t('newList.noItems') });
      return;
    }
    setBusy(true);
    setError(null);
    try {
      if (!pool.member) await joinPool(token);
      const fromPhoto = Boolean(loadDraft(user.id)?.fromPhoto);
      const data = await setPoolMyList(token, { items, fromPhoto });
      setPool(data);
      setLocalItems([]);
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  async function importDraft() {
    const draft = loadDraft(user.id);
    if (!draft?.items?.length) {
      navigate('/c/new', { state: { returnToPool: token } });
      return;
    }
    setLocalItems(draft.items.filter((r) => r.name?.trim()));
  }

  async function onClose() {
    setBusy(true);
    try {
      setPool(await closePool(token));
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  async function onSend() {
    setBusy(true);
    setError(null);
    try {
      const data = await sendPool(token);
      setPool(data);
      if (data.orderRequestId) navigate(`/c/requests/${data.orderRequestId}`);
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  async function onApprove() {
    setBusy(true);
    setError(null);
    try {
      setPool(await approvePoolBag(token));
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  if (!pool && !error) {
    return (
      <section className="screen">
        <Loader lines={5} />
      </section>
    );
  }

  if (error && !pool) {
    return (
      <section className="screen">
        <BackButton fallback="/c/communities" />
        <ErrorBox error={error} onRetry={() => refresh()} />
        <p className="muted small">{t('pool.mustBeMember')}</p>
        <Link to="/c/communities" className="btn btn-secondary btn-block">
          {t('pool.pickCommunity')}
        </Link>
      </section>
    );
  }

  const open = pool.status === 'OPEN';

  return (
    <section className="screen">
      <BackButton fallback="/c/communities" />
      <h2>{pool.title}</h2>
      <p className="muted small">
        {pool.communityLabel} · {t('pool.anonymousHint')}
        {pool.myAlias ? ` · ${t('pool.yourAlias', { alias: pool.myAlias })}` : ''}
      </p>

      {open && (
        <div className="card note" role="timer">
          <p className="label">{t('pool.countdown', { time: formatCountdown(secondsLeft) })}</p>
          <p className="muted small">{t('pool.windowHint', { n: pool.windowMinutes })}</p>
        </div>
      )}

      {pool.host && open && (
        <div className="card group-share-card">
          <p className="label">{t('pool.inviteTitle')}</p>
          <p className="muted small">{t('pool.inviteHint')}</p>
          <button type="button" className="btn btn-secondary btn-block" onClick={onShare} disabled={busy}>
            {t('pool.shareLink')}
          </button>
          {shareNote && <p className="muted small">{shareNote}</p>}
          <p className="muted small mono-link">{poolJoinUrl(token)}</p>
        </div>
      )}

      <p className="muted small">
        {t('pool.stats', { members: pool.memberCount, ready: pool.listsReady })}
      </p>

      <ol className="item-list group-item-list">
        {(pool.bags || []).map((bag) => (
          <li key={bag.memberId} className="item-row group-item-row">
            <div className="item-row-top">
              <span className="target-name">
                {bag.alias}
                {bag.host ? ` · ${t('pool.hostBadge')}` : ''}
                {bag.mine ? ` · ${t('pool.youBadge')}` : ''}
              </span>
              <span className="muted small">{tn('requests.items', bag.itemCount)}</span>
            </div>
            <ul className="plain-list">
              {(bag.items || []).map((it, i) => (
                <li key={i} className="muted small">
                  {it.name} · {formatQty(it.quantity)} {t(`unit.${it.unit}`)}
                </li>
              ))}
            </ul>
            {bag.fromPhoto && <p className="muted small">{t('pool.fromPhoto')}</p>}
          </li>
        ))}
      </ol>

      {open && (
        <div className="card group-add-form">
          <p className="label">{t('pool.addYourList')}</p>
          <button type="button" className="btn btn-secondary btn-block" onClick={importDraft} disabled={busy}>
            {t('pool.usePhotoOrDraft')}
          </button>
          <form onSubmit={addLocalRow}>
            <input
              className="input"
              placeholder={t('newList.namePlaceholder')}
              maxLength={60}
              value={draftItem.name}
              onChange={(e) => setDraftItem((d) => ({ ...d, name: e.target.value }))}
            />
            <div className="item-row-bottom">
              <input
                className="input item-qty"
                inputMode="decimal"
                placeholder="1"
                value={draftItem.quantity}
                onChange={(e) => setDraftItem((d) => ({ ...d, quantity: e.target.value }))}
              />
              <select
                className="input item-unit"
                value={draftItem.unit}
                onChange={(e) => setDraftItem((d) => ({ ...d, unit: e.target.value }))}
              >
                {UNITS.map((u) => (
                  <option key={u} value={u}>
                    {t(`unit.${u}`)}
                  </option>
                ))}
              </select>
            </div>
            <button type="submit" className="btn btn-secondary btn-block">
              + {t('pool.addLine')}
            </button>
          </form>
          {localItems.length > 0 && (
            <ul className="plain-list">
              {localItems.map((r) => (
                <li key={r.id} className="muted small">
                  {r.name} · {r.quantity} {t(`unit.${r.unit}`)}
                </li>
              ))}
            </ul>
          )}
          <button type="button" className="btn btn-block" onClick={onSubmitList} disabled={busy}>
            {t('pool.submitList')}
          </button>
        </div>
      )}

      {(pool.status === 'ACCEPTED' || pool.status === 'READY') && myBag && (
        <div className="card note">
          <p className="label">{t('pool.reviewBagTitle')}</p>
          <p className="muted small">{t('pool.reviewBagHint')}</p>
          <ul className="plain-list">
            {(myBag.items || []).map((it, i) => (
              <li key={i}>
                {it.name} · {formatQty(it.quantity)} {t(`unit.${it.unit}`)}
              </li>
            ))}
          </ul>
          {!myBag.pickupCode ? (
            <button type="button" className="btn btn-block" onClick={onApprove} disabled={busy}>
              {t('pool.approveAndShowOtp')}
            </button>
          ) : (
            <div className="card pickup-code-card" role="status">
              <p className="label">{t('pool.yourOtp')}</p>
              <p className="accept-best-price">{myBag.pickupCode}</p>
              <p className="muted small">{t('pool.otpHint')}</p>
            </div>
          )}
        </div>
      )}

      {error && (
        <p className="form-error" role="alert">
          {errorText(error, t)}
        </p>
      )}

      {pool.host && (pool.status === 'OPEN' || pool.status === 'CLOSED') && (
        <div className="sticky-action">
          {pool.status === 'OPEN' && (
            <button type="button" className="btn btn-secondary btn-block" onClick={onClose} disabled={busy}>
              {t('pool.closeEarly')}
            </button>
          )}
          <button
            type="button"
            className="btn btn-block"
            onClick={onSend}
            disabled={busy || pool.listsReady < 1}
          >
            {t('pool.sendToShops')}
          </button>
          <p className="muted small">{t('pool.hostChooses')}</p>
        </div>
      )}

      {pool.host && pool.orderRequestId && (pool.status === 'QUOTING' || pool.status === 'ACCEPTED') && (
        <Link to={`/c/requests/${pool.orderRequestId}`} className="btn btn-block">
          {t('pool.chooseShop')}
        </Link>
      )}
    </section>
  );
}
