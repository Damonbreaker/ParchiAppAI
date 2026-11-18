import { Link } from 'react-router-dom';
import StatusBadge from './StatusBadge.jsx';
import { formatINR } from '../lib/money.js';
import { formatDateTime, formatTime } from '../lib/time.js';
import { useT } from '../i18n/useT.js';

// "Atta, Sugar, Butter +3 more"
export function previewText(preview, itemCount, t) {
  const names = (preview || []).join(', ');
  const more = itemCount - (preview || []).length;
  return more > 0 ? `${names} ${t('requests.more', { n: more })}` : names;
}

// Customer's order in the Orders tab.
export default function RequestCard({ request }) {
  const { t, tn, lang } = useT();
  const accepted = request.acceptedStoreName != null;
  const ready = request.status === 'READY';

  return (
    <Link to={`/c/requests/${request.id}`} className={ready ? 'request-card is-ready' : 'request-card'}>
      <span className="request-card-top">
        <span className="small muted">{formatDateTime(request.createdAt, lang)}</span>
        <StatusBadge status={request.status} />
      </span>
      {accepted ? (
        <span className="request-card-order">
          <span className="request-card-preview">{request.acceptedStoreName}</span>
          <span className="request-card-total">{formatINR(request.acceptedTotal)}</span>
        </span>
      ) : (
        <span className="request-card-preview">{previewText(request.preview, request.itemCount, t)}</span>
      )}
      <span className="small muted">
        {accepted ? previewText(request.preview, request.itemCount, t) : (
          <>
            {tn('requests.items', request.itemCount)} · {tn('requests.sentTo', request.storesSentCount)}
          </>
        )}
      </span>
      {ready && <span className="request-card-ready">{t('order.readyShort')}</span>}
      {request.status === 'ACCEPTED' && request.readyBy && (
        <span className="request-card-prices">{t('order.readyBy', { time: formatTime(request.readyBy, lang) })}</span>
      )}
      {!accepted && request.quotesCount > 0 && (
        <span className="request-card-prices">
          {tn('requests.prices', request.quotesCount, { price: formatINR(request.lowestTotal) })}
        </span>
      )}
    </Link>
  );
}
