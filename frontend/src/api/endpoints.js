import { api, uploadFile } from './client.js';

// Builds "?a=1&b=2", skipping empty values.
function qs(params) {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== '') search.set(key, value);
  }
  const str = search.toString();
  return str ? `?${str}` : '';
}

const id = (value) => encodeURIComponent(value);

// A 401 here means wrong credentials, not an expired session.
export const register = (body) => api.post('/auth/register', body, { skipAuthRedirect: true });
export const login = (body) => api.post('/auth/login', body, { skipAuthRedirect: true });

export const getMe = (opts) => api.get('/me', opts);
export const updateMe = (body) => api.patch('/me', body);

// Store owner
export const getMyStore = (opts) => api.get('/stores/mine', opts);
export const createStore = (body) => api.post('/stores', body);
export const updateMyStore = (body) => api.patch('/stores/mine', body);
export const topUpParchi = (amount) => api.post('/stores/mine/parchi/top-up', { amount });
// Paged lists: -> { items, page, hasMore }. page is 0-based.
export const getStoreRequests = (status, { page = 0, size = 20 } = {}, opts) =>
  api.get(`/store/requests${qs({ status, page, size })}`, opts);
export const getStoreRequest = (requestId, opts) => api.get(`/store/requests/${id(requestId)}`, opts);
// Creates or replaces this store's quote. 409 REQUEST_CLOSED once the request is no longer open.
export const putQuote = (requestId, body) => api.put(`/store/requests/${id(requestId)}/quote`, body);
// Winning store only. ACCEPTED -> READY, then ACCEPTED/READY -> PICKED_UP with the customer's 4-digit code.
export const markReady = (requestId) => api.post(`/store/requests/${id(requestId)}/ready`);
export const markPickedUp = (requestId, pickupCode) =>
  api.post(`/store/requests/${id(requestId)}/picked-up`, { pickupCode });
export const rateCustomer = (requestId, body) => api.post(`/store/requests/${id(requestId)}/rating`, body);
export const getStoreRequestRatings = (requestId, opts) =>
  api.get(`/store/requests/${id(requestId)}/ratings`, opts);

// Customer
export const getNearbyStores = ({ lat, lng }, opts) => api.get(`/stores/nearby${qs({ lat, lng })}`, opts);
export const searchStores = ({ q, lat, lng }, opts) => api.get(`/stores/search${qs({ q, lat, lng })}`, opts);
export const getStore = (storeId, { lat, lng } = {}, opts) =>
  api.get(`/stores/${id(storeId)}${qs({ lat, lng })}`, opts);

export const createRequest = (body) => api.post('/requests', body);
export const getMyRequests = ({ page = 0, size = 20 } = {}, opts) => api.get(`/requests/mine${qs({ page, size })}`, opts);
export const getRequest = (requestId, opts) => api.get(`/requests/${id(requestId)}`, opts);
export const cancelRequest = (requestId) => api.post(`/requests/${id(requestId)}/cancel`);
// expectedTotal = the total on screen; the server answers 409 QUOTE_CHANGED if it moved.
export const acceptQuote = (requestId, quoteId, expectedTotal) => {
  const n = Number(expectedTotal);
  const total = Number.isFinite(n) ? Number(n.toFixed(2)) : expectedTotal;
  return api.post(`/requests/${id(requestId)}/accept/${id(quoteId)}`, { expectedTotal: total });
};
export const rateStore = (requestId, body) => api.post(`/requests/${id(requestId)}/rating`, body);
export const getRequestRatings = (requestId, opts) => api.get(`/requests/${id(requestId)}/ratings`, opts);

// Parchi photo: read once to fill the list -> { items }. The server never stores it.
export const scanParchi = (imageBlob, opts) => {
  const form = new FormData();
  form.append('image', imageBlob, 'parchi.jpg');
  return uploadFile('/parchi/scan', form, opts);
};

// Shopkeeper: item / qty / price list photo → base names + prices.
export const scanShopParchi = (imageBlob, opts) => {
  const form = new FormData();
  form.append('image', imageBlob, 'parchi.jpg');
  return uploadFile('/store/parchi/scan', form, opts);
};

// Catalogue polls after a scan (proxied to the local OCR service).
export const suggestCatalogue = (body) => api.post('/parchi/catalogue/suggest', body);
export const resolveCatalogue = (body) => api.post('/parchi/catalogue/resolve', body);

// Collaborative group lists (WhatsApp share link). Guest mutate endpoints skip auth redirect.
export const createSharedList = (body) => api.post('/shared-lists', body || {});
export const getSharedList = (token, guestKey, opts) =>
  api.get(`/shared-lists/${id(token)}${qs({ guestKey })}`, { skipAuthRedirect: true, ...opts });
export const addSharedItem = (token, body) =>
  api.post(`/shared-lists/${id(token)}/items`, body, { skipAuthRedirect: true });
export const updateSharedItem = (token, itemId, body) =>
  api.put(`/shared-lists/${id(token)}/items/${id(itemId)}`, body, { skipAuthRedirect: true });
export const deleteSharedItem = (token, itemId, guestKey) =>
  api.del(`/shared-lists/${id(token)}/items/${id(itemId)}${qs({ guestKey })}`, { skipAuthRedirect: true });
export const lockSharedList = (token) => api.post(`/shared-lists/${id(token)}/lock`);
export const unlockSharedList = (token) => api.post(`/shared-lists/${id(token)}/unlock`);
export const preparePlaceSharedList = (token) => api.post(`/shared-lists/${id(token)}/prepare-place`);
export const markSharedListPlaced = (token) => api.post(`/shared-lists/${id(token)}/placed`);

// Society / tower communities + timed delivery pools
export const listCommunities = (opts) => api.get('/communities', opts);
export const joinCommunity = (communityId) => api.post(`/communities/${id(communityId)}/join`);
export const createPool = (body) => api.post('/pools', body);
export const getPool = (token, opts) => api.get(`/pools/${id(token)}`, opts);
export const joinPool = (token) => api.post(`/pools/${id(token)}/join`);
export const setPoolMyList = (token, body) => api.put(`/pools/${id(token)}/my-list`, body);
export const closePool = (token) => api.post(`/pools/${id(token)}/close`);
export const sendPool = (token) => api.post(`/pools/${id(token)}/send`);
export const approvePoolBag = (token) => api.post(`/pools/${id(token)}/approve`);
export const deliverPoolBag = (requestId, body) =>
  api.post(`/store/requests/${id(requestId)}/bags/deliver`, body);
