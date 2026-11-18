import { useEffect, useRef, useState } from 'react';
import { useT } from '../i18n/useT.js';

// Full-screen photo. Scrolls in both directions; pinch zoom is the browser's own
// (no library). Tapping the photo switches between "fit width" and real size.
// Only shows photos already in memory (the customer's own scan); nothing is downloaded.
export default function PhotoViewer({ title, src, onClose }) {
  const { t } = useT();
  const closeRef = useRef(null);
  const [actual, setActual] = useState(false);

  useEffect(() => {
    closeRef.current?.focus();
    const onKey = (e) => e.key === 'Escape' && onClose();
    document.addEventListener('keydown', onKey);
    const overflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.removeEventListener('keydown', onKey);
      document.body.style.overflow = overflow;
    };
  }, [onClose]);

  return (
    <div className="viewer" role="dialog" aria-modal="true" aria-label={title}>
      <div className="viewer-bar">
        <span className="viewer-title">{title}</span>
        <button ref={closeRef} type="button" className="icon-btn viewer-close" aria-label={t('photo.close')} onClick={onClose}>
          <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
            <path d="M6 6l12 12M18 6L6 18" />
          </svg>
        </button>
      </div>
      <div className="viewer-body">
        <img
          src={src}
          alt={title}
          className={actual ? 'viewer-img is-actual' : 'viewer-img'}
          onClick={() => setActual((a) => !a)}
        />
      </div>
      <p className="viewer-hint">{t('photo.zoomHint')}</p>
    </div>
  );
}
