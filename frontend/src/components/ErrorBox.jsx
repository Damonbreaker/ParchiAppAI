import { useT } from '../i18n/useT.js';
import { formatWait, isTooMany } from '../lib/useRateLimit.js';

// Turns an ApiError into a translated message: known codes first, then a generic one.
export function errorText(error, t) {
  if (!error) return '';
  if (error.code === 'NETWORK') return t('error.network');
  if (error.code === 'TIMEOUT') return t('error.timeout');
  if (error.code === 'OFFLINE') return t('error.offline');
  if (isTooMany(error)) return t('error.tooMany', { time: formatWait(error.retryAfter || 60, t) });
  const key = `apiError.${error.code}`;
  const known = t(key);
  if (known !== key) return known;
  return t('error.server', { code: error.code || error.status || '?' });
}

export default function ErrorBox({ error, onRetry }) {
  const { t } = useT();
  return (
    <div className="card error-box" role="alert">
      <p className="error-title">{t('error.title')}</p>
      <p className="muted">{errorText(error, t)}</p>
      {onRetry && (
        <button type="button" className="btn" onClick={onRetry}>
          {t('common.retry')}
        </button>
      )}
    </div>
  );
}
