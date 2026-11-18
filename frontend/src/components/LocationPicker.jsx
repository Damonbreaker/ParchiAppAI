import { useRef, useState } from 'react';
import Field from './Field.jsx';
import {
  TEST_LOCATION,
  formatCoords,
  getCurrentPosition,
  isDemoBuild,
  mapsUrl,
  parseCoord,
  splitPastedCoords,
  validateCoords,
} from '../lib/geo.js';
import { useT } from '../i18n/useT.js';

// GPS first; if that fails, manual lat/lng (and a test location in dev).
// value: { lat, lng, label } | null. onChange receives the same shape;
// label is location.label.gps | .test | .manual.
export default function LocationPicker({ value, onChange, error, idPrefix = 'loc' }) {
  const { t } = useT();
  const [locating, setLocating] = useState(false);
  const [geoError, setGeoError] = useState(null);
  const [showManual, setShowManual] = useState(false);
  const [manual, setManual] = useState(() =>
    value ? { lat: String(value.lat), lng: String(value.lng) } : { lat: '', lng: '' }
  );
  const [manualErrors, setManualErrors] = useState({});
  const busyRef = useRef(false);

  async function useGps() {
    if (busyRef.current) return;
    busyRef.current = true;
    setLocating(true);
    setGeoError(null);
    try {
      const pos = await getCurrentPosition();
      onChange({ ...pos, label: 'location.label.gps' });
      setShowManual(false);
    } catch (err) {
      setGeoError(err.code || 'UNAVAILABLE');
      setShowManual(true);
    } finally {
      busyRef.current = false;
      setLocating(false);
    }
  }

  function useTest() {
    setManual({ lat: String(TEST_LOCATION.lat), lng: String(TEST_LOCATION.lng) });
    setManualErrors({});
    onChange({ ...TEST_LOCATION, label: 'location.label.test' });
  }

  function setManualField(name, text) {
    // Pasting "28.5050, 77.3800" into either box fills both.
    const pasted = splitPastedCoords(text);
    setManual(pasted || ((m) => ({ ...m, [name]: text })));
    setManualErrors({});
  }

  function applyManual() {
    const errs = validateCoords(manual.lat, manual.lng);
    setManualErrors(errs);
    if (errs.lat || errs.lng) return;
    onChange({ lat: parseCoord(manual.lat), lng: parseCoord(manual.lng), label: 'location.label.manual' });
  }

  return (
    <div className="location-picker">
      <button type="button" className="btn btn-block" onClick={useGps} disabled={locating} aria-busy={locating}>
        {locating && <span className="spinner" aria-hidden="true" />}
        {locating ? t('geo.locating') : t('geo.useCurrent')}
      </button>

      {geoError && (
        <p className="form-error" role="alert">
          {t(`geo.err.${geoError}`)}
        </p>
      )}

      {/* Dev / demo: Noida seed point so shopkeeper 9000000011 receives NEAREST lists. */}
      {isDemoBuild() && (
        <button type="button" className="btn btn-secondary btn-block" onClick={useTest}>
          {t('geo.useTest')}
        </button>
      )}

      {!showManual ? (
        <button type="button" className="link-btn" onClick={() => setShowManual(true)}>
          {t('geo.manualToggle')}
        </button>
      ) : (
        <div className="card manual-coords">
          <p className="muted small">{t('geo.manualHint')}</p>
          <div className="coord-row">
            <Field
              id={`${idPrefix}-lat`}
              label={t('geo.lat')}
              inputMode="decimal"
              placeholder="28.5050"
              value={manual.lat}
              error={manualErrors.lat && t(manualErrors.lat)}
              onChange={(e) => setManualField('lat', e.target.value)}
            />
            <Field
              id={`${idPrefix}-lng`}
              label={t('geo.lng')}
              inputMode="decimal"
              placeholder="77.3800"
              value={manual.lng}
              error={manualErrors.lng && t(manualErrors.lng)}
              onChange={(e) => setManualField('lng', e.target.value)}
            />
          </div>
          <button type="button" className="btn btn-secondary btn-block" onClick={applyManual}>
            {t('geo.setManual')}
          </button>
        </div>
      )}

      {value && (
        <div className="card chosen-location">
          <p className="label">{t('geo.chosen')}</p>
          <p>
            {t(value.label)} · <span className="muted small">{formatCoords(value)}</span>
          </p>
          <a href={mapsUrl(value)} target="_blank" rel="noopener noreferrer">
            {t('geo.checkMaps')}
          </a>
        </div>
      )}

      {error && <p className="field-error">{error}</p>}
    </div>
  );
}
