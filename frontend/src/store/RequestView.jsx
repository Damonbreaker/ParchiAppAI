import { useCallback, useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { getStoreRequest } from '../api/endpoints.js';
import BackButton from '../components/BackButton.jsx';
import ErrorBox from '../components/ErrorBox.jsx';
import Loader from '../components/Loader.jsx';
import QuoteLines from '../components/QuoteLines.jsx';
import OrderPanel from './OrderPanel.jsx';
import QuoteEditor from './QuoteEditor.jsx';
import { formatKm } from '../lib/geo.js';
import { formatINR } from '../lib/money.js';
import { mergeLines } from '../lib/quote.js';
import { formatDateTime, timeAgo } from '../lib/time.js';
import { formatItemQty } from '../lib/units.js';
import { useT } from '../i18n/useT.js';

// Opening this marks the request as seen on the server.
// OPEN + no quote -> editor; OPEN + quote -> summary with "Edit quote";
// outcome WON -> order panel; LOST -> "chose another store", read-only;
// otherwise closed (or a 409 while sending) -> banner, no editing.
export default function RequestView() {
  const { t, tn, lang } = useT();
  const { id } = useParams();
  const [state, setState] = useState({ status: 'loading' });
  const [editing, setEditing] = useState(false);
  const [closedOnSend, setClosedOnSend] = useState(false);

  const load = useCallback(
    (signal) => {
      setState({ status: 'loading' });
      getStoreRequest(id, { signal })
        .then((request) => setState({ status: 'done', request }))
        .catch((error) => {
          if (!signal?.aborted) setState({ status: 'error', error });
        });
    },
    [id]
  );

  useEffect(() => {
    const controller = new AbortController();
    load(controller.signal);
    return () => controller.abort();
  }, [load]);

  const onSent = useCallback((quote) => {
    setState((s) => ({ ...s, request: { ...s.request, myQuote: quote } }));
    setEditing(false);
    window.scrollTo(0, 0);
  }, []);

  const onOrderUpdate = useCallback((request) => setState({ status: 'done', request }), []);

  const onClosed = useCallback(() => {
    setClosedOnSend(true);
    setEditing(false);
    window.scrollTo(0, 0);
    load(); // fetch the real status (cancelled / accepted) for the banner
  }, [load]);

  let body;
  if (state.status === 'loading') {
    body = <Loader lines={5} />;
  } else if (state.status === 'error') {
    const notFound = state.error.status === 404;
    body = <ErrorBox error={state.error} onRetry={notFound ? undefined : () => load()} />;
  } else if (state.request.outcome === 'WON') {
    body = <OrderPanel request={state.request} onUpdate={onOrderUpdate} onReload={() => load()} />;
  } else if (state.request.outcome === 'LOST') {
    const { request } = state;
    body = (
      <>
        <h2>{t('detail.title', { id: request.id })}</h2>
        <div className="closed-banner" role="status">
          <p className="label">{t('outcome.lost')}</p>
          <p className="small">{t('storeRequest.lostHint')}</p>
        </div>
        {request.myQuote ? (
          <>
            <h3>{t('storeRequest.yourQuote')}</h3>
            <QuoteLines lines={mergeLines(request.items, request.myQuote)} total={request.myQuote.totalPrice} />
          </>
        ) : null}
      </>
    );
  } else {
    const { request } = state;
    const { myQuote } = request;
    const closed = closedOnSend || request.status !== 'OPEN';
    const showEditor = !closed && (!myQuote || editing);

    body = (
      <>
        <h2>{t('detail.title', { id: request.id })}</h2>
        <p className="muted small">
          {timeAgo(request.createdAt, lang)} · {formatDateTime(request.createdAt, lang)}
          {request.distanceKm != null && ` · ${t('storePage.away', { distance: t('unit.km', { km: formatKm(request.distanceKm) }) })}`}
        </p>

        {closed && (
          <div className="closed-banner" role="status">
            <p className="label">{t('storeRequest.closed')}</p>
            <p className="small">{t(request.status === 'CANCELLED' ? 'storeRequest.cancelledHint' : 'storeRequest.closedHint')}</p>
          </div>
        )}

        {showEditor ? (
          <QuoteEditor
            request={request}
            myQuote={myQuote}
            onSent={onSent}
            onClosed={onClosed}
            onCancel={myQuote ? () => setEditing(false) : undefined}
          />
        ) : myQuote ? (
          <>
            <div className="card quote-sent" role="status">
              <p className="quote-sent-title">
                ✓ {t('storeRequest.sentSummary', { price: formatINR(myQuote.totalPrice), n: myQuote.packingTimeMins })}
              </p>
              <p className="muted small">{t('quote.updated', { ago: timeAgo(myQuote.updatedAt, lang) })}</p>
              {!closed && (
                <button type="button" className="btn btn-secondary btn-block" onClick={() => setEditing(true)}>
                  {t('storeRequest.editQuote')}
                </button>
              )}
            </div>
            <h3>{t('storeRequest.yourQuote')}</h3>
            <QuoteLines lines={mergeLines(request.items, myQuote)} total={myQuote.totalPrice} />
          </>
        ) : (
          <>
            <h3>{tn('requests.items', request.items.length)}</h3>
            <ul className="card plain-list">
              {request.items.map((item) => (
                <li key={item.id} className="line-item">
                  <span>{item.name}</span>
                  <span className="line-qty">{formatItemQty(item, t)}</span>
                </li>
              ))}
            </ul>
          </>
        )}
      </>
    );
  }

  return (
    <section className="screen">
      <BackButton fallback="/s" />
      {body}
    </section>
  );
}
