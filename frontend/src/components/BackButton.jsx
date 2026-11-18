import { useNavigate } from 'react-router-dom';
import { useT } from '../i18n/useT.js';

// Goes back in history, or to `fallback` when the page was opened directly.
export default function BackButton({ fallback = '/c' }) {
  const { t } = useT();
  const navigate = useNavigate();
  const canGoBack = (window.history.state?.idx ?? 0) > 0;
  return (
    <button type="button" className="link-btn back-btn" onClick={() => (canGoBack ? navigate(-1) : navigate(fallback))}>
      ← {t('common.back')}
    </button>
  );
}
