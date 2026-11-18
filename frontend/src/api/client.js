// Single entry point for every backend call. In dev, /api is proxied to
// http://localhost:8080/api by vite.config.js.
const BASE = import.meta.env.VITE_API_BASE || '/api';
const TOKEN_KEY = 'kirana.token';
const TIMEOUT_MS = 15000;

export class ApiError extends Error {
  // body = the full error JSON, e.g. 409 QUOTE_CHANGED carries the current quote in body.quote.
  constructor(code, message, status, fields, body) {
    super(message);
    this.code = code;
    this.status = status;
    this.fields = fields || null;
    this.body = body && typeof body === 'object' ? body : null;
    // 429 TOO_MANY_ATTEMPTS: seconds until the server allows another try.
    const wait = Number(this.body?.retryAfterSeconds);
    this.retryAfter = Number.isFinite(wait) && wait > 0 ? wait : null;
  }
}

export function getToken() {
  try {
    return localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

export function setToken(token) {
  try {
    if (token) localStorage.setItem(TOKEN_KEY, token);
    else localStorage.removeItem(TOKEN_KEY);
  } catch {
    // storage unavailable (private mode); token lives only for this page load
  }
}

// AuthContext registers this so any 401 logs the user out.
let onUnauthorized = null;
export function setUnauthorizedHandler(fn) {
  onUnauthorized = fn;
}

async function parseBody(res) {
  const type = res.headers.get('content-type') || '';
  if (res.status === 204) return null;
  if (type.includes('application/json')) return res.json();
  return res.text();
}

// Accepts { phone: 'msg' } or [{ field, message }] and returns { phone: 'msg' }.
function normalizeFields(fields) {
  if (!fields) return null;
  if (Array.isArray(fields)) {
    const out = {};
    for (const f of fields) if (f && f.field) out[f.field] = f.message;
    return out;
  }
  return typeof fields === 'object' ? fields : null;
}

// OFFLINE when the phone knows it has no connection, otherwise NETWORK (server unreachable).
function networkError() {
  const offline = typeof navigator !== 'undefined' && navigator.onLine === false;
  return offline ? new ApiError('OFFLINE', 'No internet', 0) : new ApiError('NETWORK', 'Network error', 0);
}

// Same error shape for fetch and XHR: {code, message, fields?} from GlobalExceptionHandler.
// retryHeader: the Retry-After header, used when the body has no retryAfterSeconds.
function toApiError(status, statusText, data, skipAuthRedirect, retryHeader) {
  if (status === 401 && !skipAuthRedirect && onUnauthorized) onUnauthorized();
  const code = (data && data.code) || (status === 429 ? 'TOO_MANY_ATTEMPTS' : `HTTP_${status}`);
  const message = (data && data.message) || statusText;
  const error = new ApiError(code, message, status, normalizeFields(data && data.fields), data);
  if (!error.retryAfter && Number(retryHeader) > 0) error.retryAfter = Number(retryHeader);
  return error;
}

// timeout in ms.
export async function request(
  path,
  { method = 'GET', body, headers, signal, skipAuthRedirect = false, timeout = TIMEOUT_MS } = {}
) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort('timeout'), timeout);
  if (signal) signal.addEventListener('abort', () => controller.abort(signal.reason));

  const isForm = body instanceof FormData;
  const token = getToken();
  const init = {
    method,
    signal: controller.signal,
    headers: {
      Accept: 'application/json',
      ...(body !== undefined && !isForm ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
    body: body === undefined ? undefined : isForm ? body : JSON.stringify(body),
  };

  let res;
  try {
    res = await fetch(BASE + path, init);
  } catch (err) {
    clearTimeout(timer);
    if (signal?.aborted) throw err;
    if (controller.signal.aborted) throw new ApiError('TIMEOUT', 'Request timed out', 0);
    throw networkError();
  }

  try {
    const data = await parseBody(res).catch(() => null);
    if (!res.ok) throw toApiError(res.status, res.statusText, data, skipAuthRedirect, res.headers.get('retry-after'));
    return data;
  } catch (err) {
    if (err instanceof ApiError) throw err;
    if (signal?.aborted) throw err;
    throw new ApiError(controller.signal.aborted ? 'TIMEOUT' : 'NETWORK', 'Network error', 0);
  } finally {
    clearTimeout(timer);
  }
}

// POST multipart with upload progress. fetch can't report upload progress, so this
// uses XMLHttpRequest with the same auth header, errors and 401 logout as request().
// onProgress(fraction 0..1); it reports 1 when the last byte has left the phone.
// Abort via signal rejects with an AbortError.
export function uploadFile(path, formData, { onProgress, signal, timeout = 60000, skipAuthRedirect = false } = {}) {
  return new Promise((resolve, reject) => {
    const abortError = () => new DOMException('Upload cancelled', 'AbortError');
    if (signal?.aborted) {
      reject(abortError());
      return;
    }
    const xhr = new XMLHttpRequest();
    const onAbort = () => xhr.abort();
    const done = () => signal?.removeEventListener('abort', onAbort);

    xhr.open('POST', BASE + path);
    xhr.timeout = timeout;
    xhr.setRequestHeader('Accept', 'application/json');
    const token = getToken();
    if (token) xhr.setRequestHeader('Authorization', `Bearer ${token}`);

    if (onProgress) {
      xhr.upload.onprogress = (e) => e.lengthComputable && onProgress(Math.min(1, e.loaded / e.total));
      xhr.upload.onload = () => onProgress(1);
    }
    xhr.onload = () => {
      done();
      let data = null;
      try {
        data = xhr.responseText ? JSON.parse(xhr.responseText) : null;
      } catch {
        data = null;
      }
      if (xhr.status >= 200 && xhr.status < 300) resolve(data);
      else reject(toApiError(xhr.status, xhr.statusText, data, skipAuthRedirect, xhr.getResponseHeader('retry-after')));
    };
    xhr.onerror = () => {
      done();
      reject(networkError());
    };
    xhr.ontimeout = () => {
      done();
      reject(new ApiError('TIMEOUT', 'Request timed out', 0));
    };
    xhr.onabort = () => {
      done();
      reject(abortError());
    };
    signal?.addEventListener('abort', onAbort);
    xhr.send(formData);
  });
}

export const api = {
  get: (path, opts) => request(path, { ...opts, method: 'GET' }),
  post: (path, body, opts) => request(path, { ...opts, method: 'POST', body }),
  put: (path, body, opts) => request(path, { ...opts, method: 'PUT', body }),
  patch: (path, body, opts) => request(path, { ...opts, method: 'PATCH', body }),
  del: (path, opts) => request(path, { ...opts, method: 'DELETE' }),
};
