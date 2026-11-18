import { useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import LocationPicker from '../components/LocationPicker.jsx';
import { TEST_LOCATION, ensureDemoLocation, getSavedLocation, isDemoBuild, saveLocation } from '../lib/geo.js';
import { useT } from '../i18n/useT.js';

// First-visit location prompt, also opened by "Change" on home and review.
// Pass state={{ returnTo }} to come back somewhere other than home.
export default function LocationSetup() {
  const { t } = useT();
  const navigate = useNavigate();
  const returnTo = useLocation().state?.returnTo || '/c';
  const [saved] = useState(() => (isDemoBuild() ? ensureDemoLocation() : getSavedLocation()));
  const [location, setLocation] = useState(
    () => saved || (isDemoBuild() ? { ...TEST_LOCATION, label: 'location.label.test' } : null)
  );

  function onContinue() {
    saveLocation(location);
    navigate(returnTo, { replace: true });
  }

  return (
    <section className="screen">
      <h2>{t('location.title')}</h2>
      <p className="muted">{t('location.subtitle')}</p>
      <LocationPicker idPrefix="cust-loc" value={location} onChange={setLocation} />
      <button type="button" className="btn btn-block" onClick={onContinue} disabled={!location}>
        {t('location.continue')}
      </button>
      {saved && (
        <button type="button" className="btn btn-secondary btn-block" onClick={() => navigate(returnTo, { replace: true })}>
          {t('common.cancel')}
        </button>
      )}
    </section>
  );
}
