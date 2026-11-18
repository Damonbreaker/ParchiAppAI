import { useT } from '../i18n/useT.js';

// Skeleton block so the screen is never blank while waiting on a slow network.
export default function Loader({ lines = 3 }) {
  const { t } = useT();
  return (
    <div className="loader" role="status" aria-live="polite">
      <span className="visually-hidden">{t('common.loading')}</span>
      {Array.from({ length: lines }, (_, i) => (
        <div key={i} className="skeleton" style={{ width: `${90 - i * 15}%` }} />
      ))}
    </div>
  );
}
