# Kirana Parchi App — Build Plan

Last updated: Nov 10, 2026

## Overview and decisions

A mobile-first web app that connects customers with nearby kirana stores: the customer sends a grocery list (parchi), up to 5 nearby stores quote a price and packing time, and the customer accepts one and picks the order up. The app only connects the two sides; it handles no payment or delivery.

| Decision | Choice |
| --- | --- |
| Users | Two roles: Customer and Store Owner |
| List input | Photo of parchi (OCR), typed list, or both, always editable before sending |
| Who gets the request | Nearest 5 open stores, or one specific store picked through search |
| Fulfilment | Customer pickup after packing time; no delivery |
| Payment | Nothing in the app; settled at the store (cash/UPI) |
| Language | Hindi and English, switchable |
| Request lifetime | Stays open until the customer accepts a quote or cancels |
| After accept | Request locks; other stores can no longer quote; both sides see each other's contact details |
| Frontend | React (Vite), built for low-end phones |
| Backend | Spring Boot (Java) REST API |
| Database | Local PostgreSQL with dummy seed data |
| Hosting | Out of scope for now; everything runs locally |
| Repos | Two folders: `kirana-frontend` and `kirana-backend` |

## User flows

There are three ways for a customer to start a request and one quoting flow for stores; all of them end at the same accept-and-lock step.

### Customer: register and log in

1. Register with name, phone, password and role = Customer.
2. On first use, allow location (browser geolocation) or pick an area manually.
3. Choose language: Hindi or English (changeable any time).

### Customer: create a request

1. Choose how to add items:
   - **Upload parchi photo**: take or pick a photo; it is compressed in the browser, uploaded, and read once by OCR into a list of items. The photo is never saved, and stores never see it.
   - **Type the list**: add rows of item name + quantity + unit by hand.
2. Review screen: edit any item's name, quantity or unit; add or delete rows. OCR output is never sent without this step.
3. Choose who gets it:
   - **Nearest stores** (default): the 5 nearest open stores within the radius.
   - **A specific store**: search stores by name or area, open the store's page, and send to that store only.
4. Send. The request status becomes Open.

### Store owner: register and quote

1. Register with role = Store Owner, then add the store: name, address, phone, location pin, open/closed toggle.
2. Incoming requests list shows open requests sent to this store, newest first.
3. Open a request and, for each item, set:
   - offered quantity (can be more or less than asked, as per stock)
   - price for that line
   - status: Available, Partial or Unavailable
4. Enter packing time in minutes. The total price is calculated from the lines.
5. Submit the quote. The store can edit its quote until the customer accepts one.

### Customer: compare and accept

1. Quotes list shows each store's total price, packing time, distance, and how many items are fully available.
2. Open a quote to see item-by-item changes (quantity changed, unavailable).
3. Accept one quote. The request locks: other quotes become Rejected and those stores see the request as closed.
4. Customer sees the store's name, address, phone and ready-by time; the store sees the customer's name and phone.
5. Customer goes to the store and picks up the order.

### Customer: other actions

- Cancel an open request at any time before accepting.
- See past requests and their status (order history).

## Data model

Seven tables. `request_target` records which stores received a request, so the same model covers both "nearest 5" and "one specific store".

| Table | Key fields | Notes |
| --- | --- | --- |
| `users` | id, name, phone (unique), password_hash, role (CUSTOMER / STORE_OWNER), preferred_lang (en / hi), created_at | One account = one role |
| `stores` | id, owner_id → users, name, address, area, phone, lat, lng, is_open, created_at | One store per owner for now; index on name + area for search |
| `order_requests` | id, customer_id → users, lat, lng, source (PHOTO / MANUAL), mode (NEAREST / DIRECT), status, accepted_quote_id (nullable), version, created_at, updated_at | `version` = JPA optimistic lock |
| `request_items` | id, request_id, name, quantity (decimal), unit, position | Unit enum: PCS, G, KG, ML, L, PACKET, DOZEN |
| `request_target` | id, request_id, store_id, distance_km, seen_at (nullable) | 5 rows for NEAREST, 1 for DIRECT |
| `quotes` | id, request_id, store_id, total_price, packing_time_mins, status, created_at, updated_at | Unique (request_id, store_id): one quote per store |
| `quote_items` | id, quote_id, request_item_id, offered_qty, unit, line_price, status (AVAILABLE / PARTIAL / UNAVAILABLE) | One row per request item |

Rules:

- Store quantity as `quantity + unit`, never free text like "1kg".
- Money as `DECIMAL(10,2)` in rupees.
- `total_price` is computed on the server from `quote_items`, never trusted from the client.
- Item names accept Hindi (Devanagari) and English; the database uses UTF-8.

## Status flows

A request stays Open until the customer accepts a quote or cancels; accepting flips the request and the chosen quote to Accepted in one database transaction.

```
REQUEST
  DRAFT --send--> OPEN --accept--> ACCEPTED --packed--> READY --collected--> PICKED_UP
                   |
                   +--customer cancels--> CANCELLED

QUOTE (one per store)
  SUBMITTED --chosen by customer--> ACCEPTED      (same transaction as request -> ACCEPTED)
      |
      +--another quote accepted, or request cancelled--> REJECTED
```

- The store marks the order **Ready** when packed and **Picked up** when the customer collects it.
- A store can edit its Submitted quote while the request is Open.
- Accept runs as `UPDATE order_requests SET status='ACCEPTED' ... WHERE id=? AND status='OPEN'` (or JPA `@Version`). If 0 rows change, someone else got there first and the API returns 409 Conflict.

## API endpoints

All routes sit under `/api`, use JSON, and need a JWT except register and login. Role checks: `C` = customer only, `S` = store owner only.

| Method | Path | Role | Purpose |
| --- | --- | --- | --- |
| POST | `/auth/register` | public | Create user (name, phone, password, role) |
| POST | `/auth/login` | public | Returns JWT + user info |
| GET | `/me` | any | Current user |
| PATCH | `/me` | any | Update name, preferred language |
| POST | `/stores` | S | Create own store |
| GET | `/stores/mine` | S | Own store details |
| PATCH | `/stores/mine` | S | Edit store, toggle open/closed |
| GET | `/stores/search?q=&lat=&lng=` | C | Search stores by name or area, sorted by distance |
| GET | `/stores/{id}` | C | Store page |
| POST | `/parchi/scan` (multipart image) | C | OCR → returns `{ items: [{name, quantity, unit, confident}] }` for the editor; the photo is not stored |
| POST | `/requests` | C | Create and send: items, lat/lng, mode (NEAREST / DIRECT), storeId if DIRECT, fromPhoto (boolean) |
| GET | `/requests/mine` | C | Customer's requests, newest first |
| GET | `/requests/{id}` | C | Request + items + all quotes |
| POST | `/requests/{id}/cancel` | C | Cancel an Open request |
| POST | `/requests/{id}/accept/{quoteId}` | C | Accept a quote; 409 if no longer Open |
| GET | `/store/requests?status=` | S | Requests targeted at this store |
| GET | `/store/requests/{id}` | S | Request detail + own quote if any |
| PUT | `/store/requests/{id}/quote` | S | Create or update own quote (items + packing time) |
| POST | `/store/requests/{id}/ready` | S | Mark accepted order Ready |
| POST | `/store/requests/{id}/picked-up` | S | Mark Picked up |

Polling: the customer's request page calls `GET /requests/{id}` every 15 s while Open; the store's list calls `GET /store/requests` every 15 s. Both stop when the tab is hidden (`document.visibilityState`).

## Tech stack and lightweight rules

The frontend targets a JS bundle under 150 KB gzipped and must stay usable on a 2 GB RAM Android phone on a slow 3G/4G connection.

### Frontend (kirana-frontend)

- Vite + React + React Router. No UI library (no MUI, Bootstrap or Tailwind runtime); plain CSS with CSS variables.
- Built-in `fetch` in a small `api.js` wrapper; no axios. No Redux: React context for auth, plain state elsewhere.
- Route-level code splitting with `React.lazy`: customer and store screens are separate chunks.
- Photo compression in the browser before upload: canvas resize to max 1280 px, JPEG quality 0.7 (about 150–300 KB).
- Mobile-first CSS: design at 360 px wide first, tap targets at least 44 px, bottom navigation bar, system fonts only.
- Inputs use `inputmode="decimal"` for quantity and price so the number keypad opens.
- Show skeletons and clear error/retry states; never a blank screen on slow network.
- Check bundle size with `vite build` on every phase; swap to Preact (`@preact/compat`) only if the budget is broken.

### Hindi + English (i18n)

- No i18n library. Two JSON files, `en.json` and `hi.json`, plus a tiny `useT()` hook that returns `t('key')`.
- Language stored in `localStorage` and in the user's `preferred_lang`.
- Item names are whatever the user types or OCR returns; only UI labels are translated.
- Unit labels translated too (kg = किलो, packet = पैकेट).

### Backend (kirana-backend)

- Java 21, Spring Boot 3, Maven.
- Spring Web, Spring Data JPA, Validation, Spring Security with JWT (stateless).
- PostgreSQL locally; Flyway migrations for schema, plus a seed migration with dummy users, ~15 stores around one test location, and sample requests.
- Nearest-stores query: Haversine formula in a native SQL query, filter `is_open = true` and radius (default 3 km, in config), `LIMIT 5`.
- OCR behind an interface `ParchiReader` with `MockParchiReader` (returns fixed sample items) for local work; a real implementation (vision AI) plugs in later.
- Parchi photos are read once and discarded; nothing is written to disk or the DB.
- Global exception handler returning `{code, message}` JSON; DTOs for every request/response, never entities.
- CORS allowed for the Vite dev server (`http://localhost:5173`).

## Folder structure

Each folder gets this `PLAN.md` and a `CLAUDE.md` (rules for the coding chat) at its root.

```
kirana-backend/
  CLAUDE.md
  PLAN.md
  pom.xml
  src/main/java/com/kirana/
    KiranaApplication.java
    config/        SecurityConfig, CorsConfig, JwtUtil
    auth/          AuthController, AuthService, dto/
    user/          User, UserRepository, Role
    store/         Store, StoreController, StoreService, StoreRepository, dto/
    request/       OrderRequest, RequestItem, RequestTarget, RequestController, RequestService, dto/
    quote/         Quote, QuoteItem, StoreQuoteController, QuoteService, dto/
    parchi/        ParchiReader, MockParchiReader, ParchiController
    common/        Unit, ApiError, GlobalExceptionHandler
  src/main/resources/
    application.yml
    db/migration/  V1__schema.sql, V2__seed.sql

kirana-frontend/
  CLAUDE.md
  PLAN.md
  index.html
  vite.config.js
  src/
    main.jsx, App.jsx, routes.jsx
    api/           client.js (fetch wrapper + JWT), endpoints.js
    auth/          AuthContext.jsx, Login.jsx, Register.jsx
    i18n/          en.json, hi.json, useT.js
    lib/           compressImage.js, geo.js, units.js, usePolling.js
    components/    BottomNav, ItemRow, QuoteCard, Loader, ErrorBox
    customer/      Home, NewRequest, ReviewItems, StoreSearch, StorePage, RequestDetail, History
    store/         StoreSetup, Inbox, QuoteEditor, Orders
    styles/        base.css
```

## Build phases

Eight phases (0–7), each ending in something you can click through on your phone over local Wi-Fi. Finish and test one before starting the next.

- [ ] **Phase 0 — Setup.** Both projects created, `CLAUDE.md` + `PLAN.md` in each, Postgres running, backend `/api/health` returns OK, frontend shows a blank mobile shell with bottom nav.
- [ ] **Phase 1 — Auth + roles.** Register, login, JWT, role-based routing (customer home vs store home), Hindi/English toggle.
- [ ] **Phase 2 — Stores.** Store setup form with location pin, open/closed toggle, seed data of ~15 stores, store search page and store page for customers.
- [ ] **Phase 3 — Typed request.** Customer types a list, reviews it, sends to nearest 5 or a specific store. Store inbox shows it.
- [x] **Phase 4 — Quotes.** Store quote editor (quantity, price, status per item, packing time). Customer sees quotes update by polling.
- [x] **Phase 5 — Accept and lock.** Accept with 409 handling, other quotes rejected, contacts revealed, Ready and Picked up, history.
- [x] **Phase 6 — Parchi photo.** Camera/gallery upload, browser compression, `/parchi/scan` with the mock reader, results on the review screen.
- [x] **Phase 7 — Polish.** Bundle size check, slow-network testing (Chrome DevTools "Slow 4G" + CPU throttle 4x), empty and error states, Hindi text review.

To test on a real phone: run Vite with `--host`, open `http://<your-PC-IP>:5173` on a phone on the same Wi-Fi. Geolocation needs HTTPS outside localhost, so use a manual location picker or a tunnel (for example `ngrok`) when testing location on the phone.

## Prompts for the development chat

Paste these into your coding chat in order. Save this file as `PLAN.md` in both folders first, so the coding chat always has the full plan.

### Prompt 1 — backend Phase 0

```
Read CLAUDE.md and PLAN.md in this folder. We are on Phase 0.
Create the Spring Boot 3 project (Java 21, Maven) with: Spring Web, Spring Data JPA, Validation, Spring Security, PostgreSQL driver, Flyway, Lombok, and a JWT library.
Set up the package structure from PLAN.md, application.yml for a local Postgres database named kirana, CORS for http://localhost:5173, and a public GET /api/health endpoint.
Write V1__schema.sql with all seven tables from PLAN.md. Do not build features yet.
Tell me the exact commands to create the database and run the app on Windows.
```

### Prompt 2 — frontend Phase 0

```
Read CLAUDE.md and PLAN.md in this folder. We are on Phase 0.
Create a Vite + React project with React Router. Set up the folder structure from PLAN.md, base.css with CSS variables for a mobile-first layout, the useT() i18n hook with en.json and hi.json, the api/client.js fetch wrapper, and an app shell with a bottom navigation bar.
Call GET /api/health on the home screen and show the result.
Configure Vite to run with --host so I can open it on my phone on the same Wi-Fi.
Run vite build and tell me the gzipped JS size.
```

### Prompt for every next phase

```
Read PLAN.md. We are now on Phase <N>: <copy the phase line from PLAN.md>.
First list the files you will create or change and any question you have. Wait for my OK.
Then build it, and finish by telling me how to test it step by step (curl for backend, clicks on the phone for frontend).
```

Run the backend and frontend prompt for the same phase one after the other: backend first, so the frontend has a real API to call.

## Open questions and later

None of these block Phase 0; decide each before the phase that needs it.

- [ ] **Pickup code:** show the customer a 4-digit code that the store enters at handover, so the wrong person can't collect the order? (Phase 5)
- [ ] **Open requests piling up:** requests stay open with no time limit. Should stores be able to dismiss a request they can't serve, and should an open request with no quotes after some hours prompt the customer to resend to more stores? (Phase 4)
- [ ] **Direct-store request:** if the chosen store doesn't quote, offer "send to nearby stores too"? (Phase 3)
- [ ] **Radius:** 3 km default; should the customer be able to widen it? (Phase 2)
- [ ] **Login:** phone + password for now; switch to phone OTP before going live.
- [ ] **Real OCR:** pick a vision AI provider and test it on 20 real parchi photos (Hindi and English) before going live.
- [ ] **Later:** push notifications (PWA), store ratings, repeat last order, store catalogue with standard prices for faster quoting.
