import { useCallback } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useStore } from './StoreContext.jsx';
import { getStoreRequests } from '../api/endpoints.js';
import ErrorBox, { errorText } from '../components/ErrorBox.jsx';
import LoadMore from '../components/LoadMore.jsx';
import { previewText } from '../components/RequestCard.jsx';
import StatusBadge from '../components/StatusBadge.jsx';
import { StoreCardSkeleton } from '../components/StoreCard.jsx';
import { formatKm } from '../lib/geo.js';
import { formatINR } from '../lib/money.js';
import { formatTime, timeAgo } from '../lib/time.js';
import { usePagedList } from '../lib/usePagedList.js';
import { usePolling } from '../lib/usePolling.js';
import { useT } from '../i18n/useT.js';

const POLL_MS = 15000;
const TABS = [
  { status: 'OPEN', key: 'inbox.tabOpen', empty: 'inbox.emptyOpen' },
  { status: 'ORDERS', key: 'inbox.tabOrders', empty: 'inbox.emptyOrders' },
  { status: 'CLOSED', key: 'inbox.tabClosed', empty: 'inbox.emptyClosed' },
];

// Closed tab: what happened to this request, from this store's side.
function outcomeBadge(request) {
  if (request.outcome === 'WON') return { key: 'outcome.pickedUp', cls: 'badge-quoted' };
  if (request.outcome === 'LOST') return { key: 'outcome.lost', cls: 'badge-closed' };
  return { key: 'outcome.cancelled', cls: 'badge-closed' };
}

function InboxCard({ request, tab }) {
  const { t, tn, lang } = useT();
  const isOpenTab = tab === 'OPEN';
  const cls = ['request-card'];
  if (isOpenTab && request.isNew) cls.push('is-new');
  if (request.status === 'READY') cls.push('is-ready');

  let badges;
  if (tab === 'ORDERS') {
    badges = <StatusBadge status={request.status} />;
  } else if (tab === 'CLOSED') {
    const b = outcomeBadge(request);
    badges = <span className={`badge ${b.cls}`}>{t(b.key)}</span>;
  } else {
    badges = (
      <>
        {request.myQuoteTotal != null ? (
          <span className="badge badge-quoted">{t('inbox.quoted', { price: formatINR(request.myQuoteTotal) })}</span>
        ) : (
          <span className="badge badge-unquoted">{t('inbox.notQuoted')}</span>
        )}
        {request.isNew && <span className="badge badge-new">{t('inbox.new')}</span>}
      </>
    );
  }

  return (
    <Link to={`/s/requests/${request.id}`} className={cls.join(' ')}>
      <span className="request-card-top">
        <span className="small muted">
          {isOpenTab && request.isNew && <span className="new-dot" aria-hidden="true" />}
          {timeAgo(request.createdAt, lang)}
        </span>
        <span className="request-card-badges">{badges}</span>
      </span>
      {tab === 'ORDERS' ? (
        <>
          <span className="request-card-order">
            <span className="request-card-preview">{request.customerName}</span>
            <span className="request-card-total">{formatINR(request.myQuoteTotal)}</span>
          </span>
          <span className="small muted">{previewText(request.preview, request.itemCount, t)}</span>
          {request.status === 'ACCEPTED' && request.readyBy && (
            <span className="request-card-prices">{t('order.readyBy', { time: formatTime(request.readyBy, lang) })}</span>
          )}
        </>
      ) : (
        <>
          <span className="request-card-preview">{previewText(request.preview, request.itemCount, t)}</span>
          <span className="small muted">
            {tn('requests.items', request.itemCount)}
            {request.distanceKm != null && ` · ${t('unit.km', { km: formatKm(request.distanceKm) })}`}
            {tab === 'CLOSED' && request.myQuoteTotal != null && ` · ${formatINR(request.myQuoteTotal)}`}
          </span>
        </>
      )}
    </Link>
  );
}

export default function Inbox() {
  const { t } = useT();
  const { store } = useStore();
  // The tab lives in the URL so Back from a request returns to the same tab.
  const [params, setParams] = useSearchParams();
  const tab = TABS.find((x) => x.status === params.get('tab')) || TABS[0];
  const fetchPage = useCallback((paging, opts) => getStoreRequests(tab.status, paging, opts), [tab.status]);
  // Keyed by tab, so a stale list is never drawn under a new tab.
  const { state, items, refresh, loadMore } = usePagedList(fetchPage, tab.status);
  const refreshing = Boolean(state.refreshing);

  usePolling(() => refresh(true), POLL_MS);

  let body;
  if (state.status === 'error' && state.key === tab.status) {
    body = <ErrorBox error={state.error} onRetry={() => refresh(false)} />;
  } else if (!items) {
    body = <StoreCardSkeleton count={4} />;
  } else if (items.length === 0) {
    body = (
      <div className="card empty">
        <p className="muted">{t(tab.empty)}</p>
      </div>
    );
  } else {
    body = (
      <>
        <div className="store-list">
          {items.map((request) => (
            <InboxCard key={request.id} request={request} tab={tab.status} />
          ))}
        </div>
        <LoadMore state={state} onLoadMore={loadMore} />
      </>
    );
  }

  return (
    <section className="screen">
      <div className="section-head">
        <h2>{t('nav.requests')}</h2>
        <button type="button" className="link-btn" onClick={() => refresh(true)} disabled={refreshing} aria-busy={refreshing}>
          {refreshing && <span className="spinner" aria-hidden="true" />} {t('common.refresh')}
        </button>
      </div>

      {!store.isOpen && (
        <div className="target-banner">
          <span>{t('inbox.storeClosed')}</span>
          <Link to="/s/store" className="link-btn">
            {t('inbox.openStore')}
          </Link>
        </div>
      )}

      <div className="tabs" role="tablist">
        {TABS.map((x) => (
          <button
            key={x.status}
            type="button"
            role="tab"
            aria-selected={x.status === tab.status}
            className="tab"
            onClick={() => setParams(x === TABS[0] ? {} : { tab: x.status }, { replace: true })}
          >
            {t(x.key)}
          </button>
        ))}
      </div>

      {tab.status === 'OPEN' && items && items.some((r) => r.myQuoteTotal == null) && (
        <div className="card note" role="status">
          <p className="label">{t('inbox.fomoOpenTitle')}</p>
          <p className="muted small">{t('inbox.fomoOpenHint')}</p>
        </div>
      )}

      {tab.status === 'CLOSED' && items && items.some((r) => r.outcome === 'LOST') && (
        <div className="card note" role="status">
          <p className="label">{t('inbox.fomoLostTitle')}</p>
          <p className="muted small">{t('inbox.fomoLostHint')}</p>
        </div>
      )}

      {items && state.refreshError && <p className="field-error">{errorText(state.refreshError, t)}</p>}
      {body}
    </section>
  );
}
