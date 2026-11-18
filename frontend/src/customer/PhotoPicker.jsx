import { useRef } from 'react';
import { useT } from '../i18n/useT.js';

// "Take photo of parchi" (opens the camera) and "Choose from gallery".
// File inputs work over plain http on the phone, unlike GPS.
export default function PhotoPicker({ onPick, compact = false }) {
  const { t } = useT();
  const cameraRef = useRef(null);
  const galleryRef = useRef(null);

  function onChange(e) {
    const file = e.target.files && e.target.files[0];
    e.target.value = ''; // picking the same photo again still fires onChange
    if (file) onPick(file);
  }

  const cls = compact ? 'start-option is-compact' : 'start-option';
  return (
    <>
      <button type="button" className={cls} onClick={() => cameraRef.current?.click()}>
        <span className="start-icon" aria-hidden="true">📷</span>
        <span className="start-text">
          <span className="start-title">{t('start.camera')}</span>
          {!compact && <span className="muted small">{t('start.cameraHint')}</span>}
        </span>
      </button>
      <button type="button" className={cls} onClick={() => galleryRef.current?.click()}>
        <span className="start-icon" aria-hidden="true">🖼️</span>
        <span className="start-text">
          <span className="start-title">{t('start.gallery')}</span>
          {!compact && <span className="muted small">{t('start.galleryHint')}</span>}
        </span>
      </button>
      <input ref={cameraRef} type="file" accept="image/*" capture="environment" hidden onChange={onChange} />
      <input ref={galleryRef} type="file" accept="image/*" hidden onChange={onChange} />
    </>
  );
}
