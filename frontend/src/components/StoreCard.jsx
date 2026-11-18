import { Link } from 'react-router-dom';
import { formatKm } from '../lib/geo.js';
import { qualityLabel } from './RatingForm.jsx';
import { useT } from '../i18n/useT.js';

export function OpenBadge({ isOpen }) {
  const { t } = useT();
  return <span className={isOpen ? 'badge badge-open' : 'badge badge-closed'}>{t(isOpen ? 'store.open' : 'store.closed')}</span>;
}

function Stars({ avg }) {
  if (avg == null || !(avg > 0)) return null;
  return (
    <span className="store-rating" title={String(avg)}>
      <span aria-hidden="true">★</span> {Number(avg).toFixed(1)}
    </span>
  );
}

// Used by customer home (nearby) and store search.
export default function StoreCard({ store }) {
  const { t } = useT();
  const tops = store.topQualities || [];
  return (
    <Link to={`/c/stores/${store.id}`} className={store.isOpen ? 'store-card' : 'store-card is-closed'}>
      <span className="store-card-main">
        <span className="store-card-name">{store.name}</span>
        <span className="muted small">{store.area}</span>
        {(store.ratingAvg > 0 || tops.length > 0) && (
          <span className="store-card-reviews">
            <Stars avg={store.ratingAvg} />
            {store.ratingCount > 0 && (
              <span className="muted small">({store.ratingCount})</span>
            )}
            {tops.slice(0, 2).map((q) => (
              <span key={q} className="review-pill">
                {qualityLabel(t, q)}
              </span>
            ))}
          </span>
        )}
      </span>
      <span className="store-card-side">
        {store.distanceKm != null && <span className="small">{t('unit.km', { km: formatKm(store.distanceKm) })}</span>}
        <OpenBadge isOpen={store.isOpen} />
      </span>
    </Link>
  );
}

export function StoreCardSkeleton({ count = 3 }) {
  const { t } = useT();
  return (
    <div className="store-list" role="status" aria-live="polite">
      <span className="visually-hidden">{t('common.loading')}</span>
      {Array.from({ length: count }, (_, i) => (
        <div key={i} className="store-card skeleton-card" aria-hidden="true">
          <span className="store-card-main">
            <span className="skeleton" style={{ width: '60%' }} />
            <span className="skeleton" style={{ width: '35%' }} />
          </span>
        </div>
      ))}
    </div>
  );
}
