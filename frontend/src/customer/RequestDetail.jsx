import { useCallback, useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { acceptQuote, cancelRequest, getRequest } from '../api/endpoints.js';
import BackButton from '../components/BackButton.jsx';
import ConfirmSheet from '../components/ConfirmSheet.jsx';
import ErrorBox, { errorText } from '../components/ErrorBox.jsx';
import Loader from '../components/Loader.jsx';
import StatusBadge from '../components/StatusBadge.jsx';
import OrderView from './OrderView.jsx';
import QuoteSheet from './QuoteSheet.jsx';
import QuotesSection from './QuotesSection.jsx';
import { formatKm } from '../lib/geo.js';
import { formatINR } from '../lib/money.js';
import { formatDateTime } from '../lib/time.js';
import { formatItemQty } from '../lib/units.js';
import { usePolling } from '../lib/usePolling.js';
import { useHistoryOverlay } from '../lib/useHistoryOverlay.js';
import { useT } from '../i18n/useT.js';

const POLL_MS = 4000;
// Statuses that can still change on the server (new prices, store marks ready, pickup).
const LIVE = ['OPEN', 'ACCEPTED', 'READY'];

export default function RequestDetail() {
  const { t, tn, lang } = useT();
  const { id } = useParams();
  const [state, setState] = useState({ status: 'loading' });
  const [refreshing, setRefreshing] = useState(false);
  // Cancel confirm lives in history state so the phone's Back button closes it.
  const [sheetOpen, openSheet, hideSheet] = useHistoryOverlay('cancel');
  const [cancelling, setCancelling] = useState(false);
  const [cancelError, setCancelError] = useState(null);
  const [accepting, setAccepting] = useState(false);
  const [acceptError, setAcceptError] = useState(null);
  const [pendingAccept, setPendingAccept] = useState(null);
  const [notice, setNotice] = useState(null);
  const controllerRef = useRef(null);
  const cancelBusy = useRef(false);
  const acceptBusy = useRef(false);
  const [params, setParams] = useSearchParams();
  const location = useLocation();
  const navigate = useNavigate();

  // silent: keep showing the current data while refreshing.
  const load = useCallback(
    (silent) => {
      controllerRef.current?.abort();
      const controller = new AbortController();
      controllerRef.current = controller;
      if (silent) setRefreshing(true);
      else setState({ status: 'loading' });
      getRequest(id, { signal: controller.signal })
        .then((request) => setState({ status: 'done', request }))
        .catch((error) => {
          if (controller.signal.aborted) return;
          setState((s) => (silent && s.request ? { ...s, refreshError: error } : { status: 'error', error }));
        })
        .finally(() => {
          if (controllerRef.current === controller) setRefreshing(false);
        });
    },
    [id],
  );

  useEffect(() => {
    load(false);
    return () => controllerRef.current?.abort();
  }, [load]);

  // Prices (OPEN) and order progress (ACCEPTED -> READY -> PICKED_UP) arrive by polling;
  // stops on its own once nothing more can change.
  const polling = state.status === 'done' && LIVE.includes(state.request.status);
  usePolling(() => load(true), POLL_MS, polling);

  // The quote sheet lives in ?quote=<id> so the phone's Back button closes it.
  // opts.accept: open already on the confirm-accept step.
  const openQuote = useCallback(
    (quoteId, opts = {}) =>
      setParams(
        { quote: String(quoteId), ...(opts.accept ? { accept: '1' } : {}) },
        { state: { quoteSheet: true } },
      ),
    [setParams],
  );
  const closeQuote = useCallback(() => {
    if (location.state?.quoteSheet) navigate(-1);
    else setParams({}, { replace: true });
  }, [location.state, navigate, setParams]);

  // Called by QuoteSheet / confirm sheet. Errors the sheet should show are re-thrown to it.
  const onAccept = useCallback(
    async (quote) => {
      if (acceptBusy.current) return;
      acceptBusy.current = true;
      setAccepting(true);
      setAcceptError(null);
      try {
        const updated = await acceptQuote(id, quote.quoteId, quote.totalPrice);
        controllerRef.current?.abort(); // an in-flight poll would bring back the OPEN state
        setNotice(null);
        setPendingAccept(null);
        setState({ status: 'done', request: updated });
        // Show the order from the top. Closing the sheet via history.back() makes the
        // browser restore the old scroll position, so scroll once that has happened.
        if (location.state?.quoteSheet) {
          window.addEventListener('popstate', () => requestAnimationFrame(() => window.scrollTo(0, 0)), { once: true });
        } else {
          window.scrollTo(0, 0);
        }
        closeQuote();
      } catch (err) {
        if (err.code === 'REQUEST_NOT_OPEN') {
          // Cancelled or already accepted elsewhere (another phone): show what it is now.
          setNotice(t('accept.notOpen'));
          setPendingAccept(null);
          closeQuote();
          load(true);
          return;
        }
        if (err.code === 'QUOTE_CHANGED' || err.code === 'QUOTE_NOT_FOUND') load(true);
        setAcceptError(errorText(err, t));
        throw err;
      } finally {
        acceptBusy.current = false;
        setAccepting(false);
      }
    },
    [id, closeQuote, load, t, location.state],
  );

  const askAccept = useCallback((quote) => {
    setAcceptError(null);
    setPendingAccept(quote);
  }, []);

  const closeSheet = useCallback(() => {
    hideSheet();
    setCancelError(null);
  }, [hideSheet]);

  async function confirmCancel() {
    if (cancelBusy.current) return;
    cancelBusy.current = true;
    setCancelling(true);
    setCancelError(null);
    try {
      const updated = await cancelRequest(id);
      if (updated && updated.id) setState({ status: 'done', request: updated });
      else load(true);
      hideSheet();
    } catch (err) {
      setCancelError(errorText(err, t));
      // Someone else may have changed it (e.g. already cancelled); show the latest.
      if (err.status === 409) load(true);
    } finally {
      cancelBusy.current = false;
      setCancelling(false);
    }
  }

  if (state.status === 'loading') {
    return (
      <section className="screen">
        <BackButton fallback="/c/orders" />
        <Loader lines={5} />
      </section>
    );
  }
  if (state.status === 'error') {
    const notFound = state.error.status === 404;
    return (
      <section className="screen">
        <BackButton fallback="/c/orders" />
        <ErrorBox error={state.error} onRetry={notFound ? undefined : () => load(false)} />
      </section>
    );
  }

  const { request, refreshError } = state;
  const isOpen = request.status === 'OPEN';
  const isOrder = request.acceptedQuoteId != null;
  const targets = request.targets || [];
  const quoteParam = params.get('quote');
  const openSheetQuote = quoteParam ? (request.quotes || []).find((q) => String(q.quoteId) === quoteParam) : null;

  return (
    <section className="screen">
      <BackButton fallback="/c/orders" />
      <div className="section-head">
        <h2>{t('detail.title', { id: request.id })}</h2>
        <StatusBadge status={request.status} />
      </div>
      <p className="muted small">
        {formatDateTime(request.createdAt, lang)} ·{' '}
        {t(request.mode === 'DIRECT' ? 'detail.modeDirect' : 'detail.modeNearest')}
      </p>

      {notice && (
        <div className="card note" role="status">
          <p className="label">{notice}</p>
        </div>
      )}
      {refreshError && <p className="field-error">{errorText(refreshError, t)}</p>}

      {isOrder ? (
        <OrderView request={request} />
      ) : (
        <>
          <QuotesSection request={request} onOpen={openQuote} onAskAccept={askAccept} />

          <h3>{tn('requests.items', request.items.length)}</h3>
          <ul className="card plain-list">
            {request.items.map((item) => (
              <li key={item.id} className="line-item">
                <span>{item.name}</span>
                <span className="muted">{formatItemQty(item, t)}</span>
              </li>
            ))}
          </ul>

          <div className="section-head">
            <h3>{t('detail.sentTo')}</h3>
            <button
              type="button"
              className="link-btn"
              onClick={() => load(true)}
              disabled={refreshing}
              aria-busy={refreshing}
            >
              {refreshing && <span className="spinner" aria-hidden="true" />} {t('common.refresh')}
            </button>
          </div>
          <ul className="card plain-list">
            {targets.map((target) => (
              <li key={target.storeId} className="line-item">
                <span className="target-main">
                  <span className="target-name">{target.storeName}</span>
                  <span className="muted small">
                    {target.area}
                    {target.distanceKm != null && ` · ${t('unit.km', { km: formatKm(target.distanceKm) })}`}
                  </span>
                </span>
                {target.quoted ? (
                  <span className="seen is-quoted">✓ {t('detail.quoted')}</span>
                ) : (
                  <span className={target.seen ? 'seen is-seen' : 'seen'}>
                    {t(target.seen ? 'detail.seen' : 'detail.notSeen')}
                  </span>
                )}
              </li>
            ))}
          </ul>

          {isOpen && (
            <button type="button" className="btn btn-secondary btn-block" onClick={openSheet}>
              {t('detail.cancel')}
            </button>
          )}
        </>
      )}

      {openSheetQuote && !isOrder && (
        <QuoteSheet
          quote={openSheetQuote}
          canAccept={isOpen}
          startConfirming={params.get('accept') === '1'}
          onAccept={onAccept}
          onClose={closeQuote}
        />
      )}

      <ConfirmSheet
        open={Boolean(pendingAccept)}
        title={
          pendingAccept
            ? t('accept.confirmTitle', {
                price: formatINR(pendingAccept.totalPrice),
                store: pendingAccept.storeName,
              })
            : ''
        }
        message={pendingAccept ? t('accept.details', { n: pendingAccept.packingTimeMins }) : ''}
        confirmLabel={
          pendingAccept ? t('accept.confirm', { price: formatINR(pendingAccept.totalPrice) }) : t('accept.confirm', { price: '' })
        }
        busy={accepting}
        error={acceptError}
        onConfirm={() => pendingAccept && onAccept(pendingAccept)}
        onCancel={() => {
          setPendingAccept(null);
          setAcceptError(null);
        }}
      />

      <ConfirmSheet
        open={sheetOpen}
        title={t('detail.cancelTitle')}
        message={t('detail.cancelMessage')}
        confirmLabel={t('detail.cancelConfirm')}
        danger
        busy={cancelling}
        error={cancelError}
        onConfirm={confirmCancel}
        onCancel={closeSheet}
      />
    </section>
  );
}
