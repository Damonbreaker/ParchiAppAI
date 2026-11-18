// Location helpers. No map library: coordinates in, a Google Maps link out.
export const TEST_LOCATION = { lat: 28.505, lng: 77.38 };

// Rough box around India, to catch swapped or mistyped coordinates.
const LAT_RANGE = [6, 38];
const LNG_RANGE = [68, 98];
const LOCATION_KEY = 'kirana.location';

const round6 = (n) => Math.round(n * 1e6) / 1e6;

// Resolves { lat, lng }; rejects with { code } where code is
// INSECURE | UNSUPPORTED | DENIED | UNAVAILABLE | TIMEOUT (keys under geo.err.*).
export function getCurrentPosition() {
  return new Promise((resolve, reject) => {
    // Browsers only allow geolocation on https or localhost.
    if (!window.isSecureContext) return reject({ code: 'INSECURE' });
    if (!('geolocation' in navigator)) return reject({ code: 'UNSUPPORTED' });
    navigator.geolocation.getCurrentPosition(
      (pos) => resolve({ lat: round6(pos.coords.latitude), lng: round6(pos.coords.longitude) }),
      (err) => reject({ code: err.code === 1 ? 'DENIED' : err.code === 3 ? 'TIMEOUT' : 'UNAVAILABLE' }),
      { enableHighAccuracy: true, timeout: 10000, maximumAge: 60000 }
    );
  });
}

export function parseCoord(value) {
  const text = String(value).trim();
  if (!/^-?\d+(\.\d+)?$/.test(text)) return null;
  return Number(text);
}

// "28.5050, 77.3800" pasted from Google Maps -> { lat: '28.5050', lng: '77.3800' }
export function splitPastedCoords(text) {
  const m = String(text).match(/^\s*(-?\d+(?:\.\d+)?)\s*[,\s]\s*(-?\d+(?:\.\d+)?)\s*$/);
  return m ? { lat: m[1], lng: m[2] } : null;
}

// Returns { lat, lng } of i18n error keys (null when valid).
export function validateCoords(latText, lngText) {
  const lat = parseCoord(latText);
  const lng = parseCoord(lngText);
  return {
    lat: lat === null ? 'geo.err.invalid' : lat < LAT_RANGE[0] || lat > LAT_RANGE[1] ? 'geo.err.latRange' : null,
    lng: lng === null ? 'geo.err.invalid' : lng < LNG_RANGE[0] || lng > LNG_RANGE[1] ? 'geo.err.lngRange' : null,
  };
}

export function formatCoords({ lat, lng }) {
  return `${lat.toFixed(5)}, ${lng.toFixed(5)}`;
}

export function mapsUrl({ lat, lng }) {
  return `https://maps.google.com/?q=${lat},${lng}`;
}

// 1.2 -> "1.2"; the unit comes from the unit.km string.
export function formatKm(km) {
  return km < 10 ? km.toFixed(1) : String(Math.round(km));
}

// Customer's saved location: { lat, lng, label } where label is an i18n key
// (location.label.gps | .test | .manual) so it follows the current language.
export function getSavedLocation() {
  try {
    const saved = JSON.parse(localStorage.getItem(LOCATION_KEY));
    if (saved && typeof saved.lat === 'number' && typeof saved.lng === 'number') return saved;
  } catch {
    // missing, corrupt or storage unavailable
  }
  return null;
}

export function saveLocation(location) {
  try {
    localStorage.setItem(LOCATION_KEY, JSON.stringify(location));
  } catch {
    // storage unavailable; the location lasts for this page load only
  }
}

/** Demo builds: always have the Noida test point so seeded shopkeepers receive requests. */
export function ensureDemoLocation() {
  const saved = getSavedLocation();
  if (saved) return saved;
  const demo = { ...TEST_LOCATION, label: 'location.label.test' };
  saveLocation(demo);
  return demo;
}

export function isDemoBuild() {
  return import.meta.env.DEV || import.meta.env.VITE_DEMO === '1';
}
