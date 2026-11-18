/** Persist / resolve the Hub URL so apps can navigate back after Hub SSO. */

const KEY = 'parchiHubReturn';

function defaultHubUrl() {
  const env = import.meta.env.VITE_HUB_URL;
  if (env) return String(env);
  const host = window.location.hostname;
  if (host === 'localhost' || host === '127.0.0.1') return 'http://127.0.0.1:5178/';
  return null;
}

/** Read ?hubReturn= from the landing URL and keep it for this tab. */
export function captureHubReturnFromUrl(params = new URLSearchParams(window.location.search)) {
  const raw = params.get('hubReturn');
  if (!raw) return params;
  try {
    const url = new URL(raw);
    if (url.protocol === 'http:' || url.protocol === 'https:') {
      sessionStorage.setItem(KEY, url.toString());
    }
  } catch {
    /* ignore bad URLs */
  }
  params.delete('hubReturn');
  return params;
}

export function getHubReturnUrl() {
  try {
    return sessionStorage.getItem(KEY) || defaultHubUrl();
  } catch {
    return defaultHubUrl();
  }
}

export function goToHub() {
  const url = getHubReturnUrl();
  if (!url) return false;
  window.location.assign(url);
  return true;
}
