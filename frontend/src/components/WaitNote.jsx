import { useT } from '../i18n/useT.js';
import { formatWait } from '../lib/useRateLimit.js';

// "Too many tries. Try again in 12 min." with a live countdown after a 429.
// role="timer" so screen readers don't re-read it every second.
export default function WaitNote({ secondsLeft }) {
  const { t } = useT();
  if (!secondsLeft) return null;
  return (
    <p className="form-error" role="timer">
      {t('error.tooMany', { time: formatWait(secondsLeft, t) })}
    </p>
  );
}
