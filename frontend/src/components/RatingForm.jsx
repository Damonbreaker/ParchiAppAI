import { useState } from 'react';
import { useT } from '../i18n/useT.js';

const STORE_TAGS = [
  'delivery',
  'friendly',
  'fast_packing',
  'large_inventory',
  'consistent_stock',
  'low_price',
  'easy_return',
  'clean_store',
  'fair_weights',
  'good_packaging',
];

const CUSTOMER_TAGS = [
  'on_time',
  'no_return_issues',
  'cooperative',
  'clear_list',
  'polite',
  'pays_promptly',
  'patient',
  'accurate_items',
];

export function qualityLabel(t, tag) {
  return t(`rating.tag.${tag}`);
}

/** 5-star + multi-select quality tags. onSubmit({ stars, qualities }). */
export default function RatingForm({ target, onSubmit, busy }) {
  const { t } = useT();
  const tags = target === 'store' ? STORE_TAGS : CUSTOMER_TAGS;
  const [stars, setStars] = useState(0);
  const [picked, setPicked] = useState(() => new Set());
  const [error, setError] = useState(null);

  function toggle(tag) {
    setPicked((prev) => {
      const next = new Set(prev);
      if (next.has(tag)) next.delete(tag);
      else next.add(tag);
      return next;
    });
  }

  async function submit(e) {
    e.preventDefault();
    if (busy) return;
    if (stars < 1) {
      setError(t('rating.err.stars'));
      return;
    }
    setError(null);
    await onSubmit({ stars, qualities: [...picked] });
  }

  return (
    <form className="rating-form card" onSubmit={submit}>
      <h3>{t(target === 'store' ? 'rating.rateStoreTitle' : 'rating.rateCustomerTitle')}</h3>
      <p className="muted small">{t('rating.hint')}</p>

      <div className="star-row" role="radiogroup" aria-label={t('rating.stars')}>
        {[1, 2, 3, 4, 5].map((n) => (
          <button
            key={n}
            type="button"
            role="radio"
            aria-checked={stars === n}
            className={n <= stars ? 'star-btn is-on' : 'star-btn'}
            onClick={() => setStars(n)}
          >
            ★
          </button>
        ))}
      </div>

      <p className="label">{t('rating.qualities')}</p>
      <div className="quality-grid">
        {tags.map((tag) => (
          <label key={tag} className={picked.has(tag) ? 'quality-chip is-on' : 'quality-chip'}>
            <input type="checkbox" checked={picked.has(tag)} onChange={() => toggle(tag)} />
            <span>{qualityLabel(t, tag)}</span>
          </label>
        ))}
      </div>

      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}

      <button type="submit" className="btn btn-block" disabled={busy} aria-busy={busy}>
        {busy ? t('common.wait') : t('rating.submit')}
      </button>
    </form>
  );
}
