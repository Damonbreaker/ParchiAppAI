import { Link } from 'react-router-dom';
import { getMyRequests } from '../api/endpoints.js';
import ErrorBox, { errorText } from '../components/ErrorBox.jsx';
import LoadMore from '../components/LoadMore.jsx';
import RequestCard from '../components/RequestCard.jsx';
import { StoreCardSkeleton } from '../components/StoreCard.jsx';
import { usePagedList } from '../lib/usePagedList.js';
import { usePolling } from '../lib/usePolling.js';
import { useT } from '../i18n/useT.js';

const POLL_MS = 15000;
const GROUPS = [
  { key: 'history.ongoing', match: (s) => s === 'ACCEPTED' || s === 'READY' },
  { key: 'history.waiting', match: (s) => s === 'OPEN' },
  { key: 'history.past', match: () => true },
];

// Orders tab: ongoing orders first, then lists waiting for prices, then past ones.
// The server sends newest first; that order is kept inside each group.
function group(requests) {
  const out = GROUPS.map((g) => ({ key: g.key, items: [] }));
  for (const r of requests) out[GROUPS.findIndex((g) => g.match(r.status))].items.push(r);
  return out.filter((g) => g.items.length);
}

export default function History() {
  const { t } = useT();
  const { state, items, refresh, loadMore } = usePagedList(getMyRequests, 'mine');

  // Keep "Ready" and new prices fresh while anything is still in progress.
  const live = Boolean(items && items.some((r) => ['OPEN', 'ACCEPTED', 'READY'].includes(r.status)));
  usePolling(() => refresh(true), POLL_MS, live);

  let body;
  if (state.status === 'error') {
    body = <ErrorBox error={state.error} onRetry={() => refresh(false)} />;
  } else if (!items) {
    body = <StoreCardSkeleton count={4} />;
  } else if (items.length === 0) {
    body = (
      <div className="card empty">
        <p className="muted">{t('history.empty')}</p>
        <Link to="/c/new" className="btn">
          {t('home.writeList')}
        </Link>
      </div>
    );
  } else {
    body = (
      <>
        {group(items).map((g) => (
          <section key={g.key}>
            <h3>{t(g.key)}</h3>
            <div className="store-list">
              {g.items.map((request) => (
                <RequestCard key={request.id} request={request} />
              ))}
            </div>
          </section>
        ))}
        <LoadMore state={state} onLoadMore={loadMore} />
      </>
    );
  }

  return (
    <section className="screen">
      <h2>{t('nav.history')}</h2>
      {state.refreshError && <p className="field-error">{errorText(state.refreshError, t)}</p>}
      {body}
    </section>
  );
}
