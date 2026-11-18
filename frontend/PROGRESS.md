# Progress

## Phase 5 — Accept and lock (2026-11-11)

**Built**
- Customer: "Accept this price" in the quote sheet, with a confirm step inside the same sheet. `expectedTotal` = the total on screen. On 409 QUOTE_CHANGED the sheet shows the new quote ("The store changed the price to ₹X") and the customer must confirm again. On REQUEST_NOT_OPEN the page refreshes and shows a notice. Double taps are blocked.
- Customer order page (after accept): Accepted → Ready → Picked up steps with times, "Ready by", READY banner, large 4-digit pickup code, store card with Call (tel:) and Directions (Google Maps), accepted lines + "Pay ₹X at the store", other prices collapsed. Polls every 15 s while OPEN / ACCEPTED / READY (paused when the tab is hidden).
- Customer Orders tab: grouped into Ongoing (ACCEPTED/READY) / Waiting for prices / Past. Order cards show store, total and ready-by; READY is highlighted. Polls every 15 s while anything is in progress.
- Store inbox: New / Orders / Closed tabs. Orders cards show customer, total, ready-by and status; Closed cards show the outcome (Picked up / Customer chose another store / Cancelled).
- Store new-order toast + `navigator.vibrate(200)` on every store screen (`OrderWatcher`). The first load only records existing orders.
- Store order view (winning store): customer + Call, ready-by, packing checklist (ticks in local state only), "Collect ₹X at pickup", Mark as ready, pickup-code sheet (one numeric input drawn as 4 boxes; wrong code keeps the sheet open), Done state.
- Store lost view: "Customer chose another store" banner, own quote read-only.

**Files**
- New: `src/components/StatusSteps.jsx`, `src/components/Toast.jsx`, `src/customer/OrderView.jsx`, `src/store/OrderPanel.jsx`, `src/store/PickupSheet.jsx`, `src/store/OrderWatcher.jsx`, `PROGRESS.md`
- Changed: `src/api/client.js` (errors keep the response body), `src/api/endpoints.js`, `src/customer/QuoteSheet.jsx`, `src/customer/RequestDetail.jsx`, `src/customer/History.jsx`, `src/components/RequestCard.jsx`, `src/store/Inbox.jsx`, `src/store/RequestView.jsx`, `src/store/index.jsx`, `src/lib/time.js` (`formatTime`), `src/i18n/en.json`, `src/i18n/hi.json`, `src/styles/base.css`, `PLAN.md` (Phase 5 ticked)

**Build (gzip JS)**: index 98.55 KB, customer 8.53 KB, store 8.41 KB, Account 5.06 KB. Total 120.6 KB of the 150 KB budget. CSS 4.71 KB.

**Tested** in Chrome with three logins (customer, Sharma, Gupta): QUOTE_CHANGED retry, accept, the losing store's view and Closed tab, Ready showing on the customer page 9.9 s after the store marked it, wrong then right pickup code, new-order toast + vibration (7 s after accept), Orders tab grouping.

**Decisions / differences from PLAN.md**
- PLAN's open question "Pickup code" is answered: the backend generates a 4-digit code and the store must enter it to mark Picked up.
- The backend sends the store contact as `acceptedStore`, not `store`. The frontend uses `acceptedStore`.
- The new-order toast shows on every store screen, not only the inbox. This adds one GET of the ORDERS list every 15 s.
- The customer Orders tab now polls every 15 s while any request is in progress (PLAN only mentions polling on the request page).
- Phase 4 follow-up: `vite.config.js` proxy now strips the `Origin` header. Without it, every POST/PUT from a phone on `http://<PC-IP>:5173` failed the backend's CORS check.

**Left open**
- Backend: `POST /store/requests/{id}/picked-up` has no limit on wrong-code attempts (only 10,000 codes). Add a cap before going live.
- Toasts only show while the app is open; push notifications are still "Later" in PLAN.md.

## Phase 6 — Parchi photo (2026-11-11)

**Built**
- `/c/new` is now a start screen with three big options: Take photo of parchi (`capture="environment"`), Choose from gallery, Type the list. It also shows "Continue your list" when a draft exists. The editor moved to `/c/new/edit`.
- `lib/compressImage.js`:
  - Decodes with `createImageBitmap(file, { imageOrientation: 'from-image' })`, falling back to `<img>`.
  - Resizes so the longest side is at most 1280 px, then exports JPEG at quality 0.7.
  - Keeps the original if it is a small JPEG that re-encoding made bigger.
  - Releases memory: `bitmap.close()`, canvas set to 0×0, object URLs revoked.
  - Rejects non-image files before decoding.
- `api/client.js`:
  - `uploadFile()` uses XHR for upload progress, with the same auth header, ApiError codes and 401 logout as other calls; it supports abort and a 60 s timeout.
  - `getBlob()` loads protected images.
  - Error building is shared through `toApiError`.
- Scan screen (`/c/new/scan`):
  - Shows the photo preview and "6.4 MB → 90 KB".
  - Progress bar while uploading, then "Reading your parchi…".
  - Cancel aborts the upload.
  - Errors: unsupported (local or server 400), too large (413), network/timeout with Retry. Retry reuses the compressed blob.
  - Empty result shows "Couldn't read this parchi", with "Try another photo" and "Type it instead".
- Editor:
  - Scanned items are added after the existing filled rows.
  - "Check the items" banner.
  - Rows with `confident=false` get an amber border and "Please check this item" until edited.
  - Parchi thumbnail opens a full-screen viewer (scroll, browser pinch zoom, tap to switch between fit and real size; Back closes it).
  - "Remove photo".
  - The draft stores `imageId` and `unsure` only, never the image. Send passes `imageId`.
- `useAuthImage(path)`: fetches the image with the token and returns an object URL, revoked on unmount.
  - Customer request page: a "Your parchi" row when `hasImage`.
  - Store request view: a "See customer's parchi" button, shown in the editor, order and lost views.
  - Both load the photo only when tapped.

**Files**
- New: `src/lib/compressImage.js`, `src/lib/parchiPhoto.js`, `src/lib/useAuthImage.js`, `src/components/PhotoViewer.jsx`, `src/components/ParchiPhoto.jsx`, `src/customer/NewListStart.jsx`, `src/customer/ScanParchi.jsx`, `src/customer/PhotoPicker.jsx`
- Changed: `src/api/client.js`, `src/api/endpoints.js`, `src/lib/draft.js`, `src/components/ItemRow.jsx`, `src/customer/index.jsx`, `src/customer/NewRequest.jsx`, `src/customer/ReviewItems.jsx`, `src/customer/RequestDetail.jsx`, `src/store/RequestView.jsx`, `src/store/OrderPanel.jsx`, `src/i18n/en.json`, `src/i18n/hi.json` (339 keys each), `src/styles/base.css`, `PLAN.md` (Phase 6 ticked)

**Build (gzip JS)**: index 100.34 KB, customer 11.22 KB, store 8.48 KB, Account (shared chunk) 6.05 KB. Total 126.1 KB of the 150 KB budget. CSS 5.25 KB. The compression code is only in the customer chunk; the viewer is in the shared chunk.

**Tested** in desktop Chrome with generated test images (not yet on a real phone):
- A 6.4 MB 3000×4000 JPEG became 90 KB at 960×1280.
- Simulated network failure, then Retry worked without recompressing.
- Progress, the "Reading…" state and Cancel (aborts the XHR).
- A PDF and a broken .jpg were both rejected locally.
- Empty result.
- Amber row cleared on edit.
- Viewer opens, and Back closes it.
- Reload shows "Photo attached".
- Request #17 was sent with `imageId`; the customer and store both saw the photo, and the store's loaded only after the tap.

**Decisions / differences from PLAN.md**
- PLAN says `imagePath` on POST /requests; the backend uses `imageId`.
- New list routes: `/c/new` is the start screen, `/c/new/scan` the scan screen, `/c/new/edit` the editor.
- A scan adds items to the current draft. A new photo replaces the earlier `imageId`, since there is one photo per list.
- After a page reload the editor shows "Photo attached" without a picture, because there is no endpoint to fetch an image before the request is sent.
- The customer's "Your parchi" also loads only on tap (saves about 100–300 KB per page open on slow data).

**Left open**
- Not yet tested on a real phone: the camera capture, EXIF rotation of real photos, and memory on a low-end device.
- The mock reader returns fixed samples; real OCR is still "Later" in PLAN.md.

## Phase 7 — Privacy + polish (2026-11-12)

**Part A: one-time scan, the store never sees the parchi**
- `/parchi/scan` returns `{ items }` only. The scan screen shows "Your photo is only read to fill the list. It is not saved."
- The editor shows the photo only from memory: the scan screen passes the Blob, and the editor makes an object URL and revokes it when you leave the editor. A reload or a trip to Review loses the preview.
- The draft keeps `fromPhoto` (no `imageId`), and Send passes `fromPhoto: true`.
- Removed:
  - `useAuthImage`, `ParchiPhoto`, `getBlob` and the image endpoints;
  - "Your parchi" on the customer request page and "See customer's parchi" in every store view;
  - "Remove photo";
  - 6 unused i18n keys and their CSS.

**Part B: polish**
- **Pagination:** the Orders tab and the 3 store inbox tabs load 20 at a time with "Load more" (`usePagedList`). Polling refreshes page 0 and merges it: duplicates are dropped, and items pushed down from page 0 are kept. "Load more" ignores double taps.
- **429:**
  - `ApiError.retryAfter` comes from `retryAfterSeconds` or the `Retry-After` header.
  - `useRateLimit` gives a live countdown ("Too many tries. Try again in 15 min.") on login (locked per mobile number), scan, send and the pickup code. The pickup input and button are disabled, and the lock survives closing the sheet (sessionStorage).
- **Offline:** a "No internet" bar from the online/offline events. Requests made while offline show "No internet…" (an `OFFLINE` error code) with Retry.
- **Back button:** `useHistoryOverlay` puts the cancel sheet, the pickup-code sheet and the photo viewer in history state, so Back closes them. The quote sheet already used `?quote=`.
- **Never a blank screen:** an `ErrorBoundary` around the routes catches render errors and failed chunk downloads; Retry reloads the page for a failed chunk. Lazy screens show a skeleton while loading.
- **Lazy loading:** `ScanParchi` with the compression code (2.3 KB) and `PhotoViewer` (0.6 KB) are separate chunks. The start screen prefetches the scan chunk.
- **Audit:** every screen already had loading, empty and error states, and every API button had a busy guard. Nothing was missing.
- **Hindi:** 9 strings fixed:
  - "लिस्ट" used everywhere (the store tab said "पर्चियाँ");
  - status OPEN is now "चालू" (it was "खुली", the same word as an open store);
  - "माप" instead of the bookish "इकाई";
  - consistent spelling of "ँ";
  - "इंटरनेट देखकर" wording.
  
  No stray English remains. All 20 screens were checked in Hindi at 360 px: nothing overflows and every button is at least 44 px tall.
- **Accessibility:**
  - Global blue `:focus-visible` ring.
  - Success green darkened to #177a34 (4.2:1 → 5.4:1).
  - `.link-btn` is now really 44 px tall (it was inline on links).
  - The header language toggle went from 36 to 44 px.
  - The custom packing-time input got a label.
  - Status is never shown by colour alone: badges, amber rows and errors all carry text.
- **Dev tools:** `SLOW_API=1500 npm run dev` delays every API response by 1.5 s. Chunks are named customer / store / shared.

**Files**
- New: `src/lib/usePagedList.js`, `src/lib/useRateLimit.js`, `src/lib/useHistoryOverlay.js`, `src/components/LoadMore.jsx`, `src/components/WaitNote.jsx`, `src/components/OfflineBar.jsx`, `src/components/ErrorBoundary.jsx`
- Deleted: `src/lib/useAuthImage.js`, `src/components/ParchiPhoto.jsx`
- Changed: `vite.config.js`, `src/App.jsx`, `src/api/client.js`, `src/api/endpoints.js`, `src/auth/Login.jsx`, `src/components/ErrorBox.jsx`, `src/components/PhotoViewer.jsx`, `src/customer/index.jsx`, `src/customer/NewListStart.jsx`, `src/customer/NewRequest.jsx`, `src/customer/ReviewItems.jsx`, `src/customer/ScanParchi.jsx`, `src/customer/History.jsx`, `src/customer/RequestDetail.jsx`, `src/store/Inbox.jsx`, `src/store/OrderWatcher.jsx`, `src/store/OrderPanel.jsx`, `src/store/PickupSheet.jsx`, `src/store/RequestView.jsx`, `src/store/QuoteEditor.jsx`, `src/lib/draft.js`, `src/lib/parchiPhoto.js`, `src/i18n/en.json`, `src/i18n/hi.json` (343 keys each), `src/styles/base.css`, `PLAN.md`

**Build (gzip JS):** index 101.63 KB, customer 9.86 KB, store 8.40 KB, shared 6.00 KB, ScanParchi 2.28 KB, PhotoViewer 0.59 KB. Total 128.8 KB of the 150 KB budget. CSS 5.32 KB.

**Tested in Chrome:**
- Scan with the new API, then `fromPhoto: true` reached the server (`source: PHOTO`), and the store view has no parchi button.
- The object URL is revoked when you leave the editor.
- Load more, double tap blocked, and the poll merge (found and fixed a bug where an item pushed off page 0 vanished).
- Back closes the cancel sheet and the pickup sheet.
- Offline bar.
- Login 429 after 5 wrong tries, with the lock per number.
- Pickup lockout after 5 wrong codes, still locked after reopening the sheet.
- Hindi at 360 px.

**Decisions / differences from PLAN.md**
- PLAN.md updated: no `image_path` and no `uploads/`; `/requests` takes `fromPhoto`; the photo is read once and never stored.
- On the Orders tab, an ongoing order beyond the first 20 only appears under "Ongoing" after "Load more", since groups are built from the loaded pages.
- With 2 or more pages loaded, an item that left a store tab (for example, cancelled) can stay visible until a tab switch or Refresh. This is the agreed trade-off for refreshing only page 0.
- The 429 lock lasts for the browser session (sessionStorage), not across app restarts. The server enforces it anyway.

**Left open**
- Not yet run: DevTools "Slow 4G" + 4x CPU on the real build. The extension can't set throttling, and `SLOW_API` needs a dev-server restart. Steps are in the regression list.
- The error boundary's chunk-failure path wasn't triggered in testing.
- Real-phone tests are still to do (camera, EXIF rotation, low-memory phone).
- Real OCR is still "Later".
