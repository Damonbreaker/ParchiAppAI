# Progress

## Phase 5 — Accept and lock, contacts, Ready, Picked up, history (2026-11-11)

**Built**
- `POST /api/requests/{id}/accept/{quoteId}` with body `{expectedTotal}`. It runs in one transaction under the request row lock (`SELECT … FOR UPDATE`, the same lock quote saves and cancel take).
  - Checks, in order: 409 `REQUEST_NOT_OPEN`, 404 `QUOTE_NOT_FOUND`, 409 `QUOTE_CHANGED` (body carries the current quote as `quote`; nothing changes), 409 `NOTHING_AVAILABLE`.
  - On success: request → ACCEPTED with `accepted_quote_id`, `accepted_at` and a 4-digit `pickup_code` (SecureRandom); chosen quote → ACCEPTED, all others → REJECTED.
- Customer detail: after accept it shows `acceptedQuoteId`, `pickupCode`, `acceptedAt`, `readyBy` (accepted_at + packing time), `readyAt`, `pickedUpAt`, and `acceptedStore` {name, address, area, phone, lat, lng} for the accepted store only. No store phones anywhere before accept.
- `/api/requests/mine` adds `acceptedStoreName`, `acceptedTotal` and `readyBy`.
- Store inbox: `?status=OPEN|ORDERS|CLOSED` (none = all).
  - Each row has `outcome` WON / LOST / CANCELLED (null while OPEN).
  - Won rows also get `customerName` and `readyBy`.
- Store detail:
  - WON: `customer` {name, phone}, `acceptedAt`, `readyBy`, `readyAt`, `pickedUpAt`.
  - LOST: nothing about the winning store or the customer.
  - The pickup code is never shown to stores.
- `POST /api/store/requests/{id}/ready` (ACCEPTED → READY) and `/picked-up {pickupCode}` (ACCEPTED/READY → PICKED_UP). Both are for the winning store only.
  - A store that didn't win gets 404; a wrong status gets 409 `INVALID_STATUS`; a wrong code gets 400 `WRONG_PICKUP_CODE`.
  - The code is compared in constant time.
- Migration `V3__accept_and_pickup.sql`: `accepted_at`, `ready_at`, `picked_up_at`, `pickup_code` (with a 4-digit check).

**Files changed**
- New:
  - `db/migration/V3__accept_and_pickup.sql`
  - `request/OrderService.java`, `request/StoreOrderController.java`, `request/StoreOutcome.java`
  - `request/dto/`: `AcceptRequest`, `PickupRequest`, `StoreContactDto`, `CustomerContactDto`
  - tests: `request/OrderFlowIntegrationTest.java`, `request/OrderServiceTest.java`
- Changed:
  - `request/`: `OrderRequest`, `RequestController`, `RequestService`, `RequestTargetRepository` (ORDERS/CLOSED queries), `InboxFilter` (+ORDERS)
  - `request/dto/`: `RequestDetailDto`, `RequestSummaryDto`, `StoreInboxItemDto`, `StoreRequestDetailDto`
  - `common/`: `ApiError` and `ApiException` (optional `quote` field), `GlobalExceptionHandler`
  - `PLAN.md`, `requests.http` (Phase 5 section, blocks 5.1–5.24)

**Tests**
- `mvnw test`: 65 tests, 0 failures.
- The new `OrderFlowIntegrationTest` (9 tests) includes the two-thread accept race: exactly one 200 and one 409 `REQUEST_NOT_OPEN`, one ACCEPTED quote, the rest REJECTED. I ran it 3 times, all green.
- The full flow was also run live against the dev database with curl. Request #12 went: accept, LOST/WON views, ready, wrong code, right code, PICKED_UP.

**Decisions / differences from PLAN.md**
- `QUOTE_CHANGED` uses the error body `{code, message, quote}`. This is the only error that carries more than `{code, message[, fields]}`.
- `outcome` is WON for every request this store won, so rows in ORDERS show WON too. CLOSED holds won-and-picked-up, lost, and cancelled requests.
- Time fields set by accept, ready and picked-up are truncated to microseconds, so the response matches what Postgres stores and later GETs return.
- PLAN.md updated: the accept body and errors, the inbox filters, the picked-up body, `OrderService`/controllers in the folder list, V3, and the pickup-code question marked decided.

**Left unfinished / open**
- No limit on wrong pickup-code attempts (10,000 possible codes); only the winning store's owner can try. Consider a lockout later; it needs a counter column in a new migration.
- Inbox and history lists are still not paginated (Phase 7).
- PLAN.md open question from Phase 4 (stores dismissing requests they can't serve, and resending stale requests) is still undecided.

## Phase 6 — Parchi photo: upload + mock OCR (2026-11-11)

**Built**
- V4 migration: `parchi_images` (UUID id, customer, file_path, content_type, size_bytes, created_at) and `order_requests.image_id` → `parchi_images`. `image_path` stays but is unused: a real FK makes ownership checks and the cleanup query simple, and the file path stays a storage detail in one table.
- OCR design in `parchi/`:
  - `ParchiReader` returns raw lines. `MockParchiReader` is active when `app.parchi.reader=mock` (the default). It picks 1 of 3 Hindi/English sample parchis by SHA-256 of the photo and waits `mock-delay-ms` (800).
  - A real OCR = one new `@Component` with `@ConditionalOnProperty(app.parchi.reader=<name>)`. Nothing else changes.
  - If the reader throws, the scan returns empty items (not an error).
- `ParchiLineParser`:
  - Quantity before or after the name; decimals, ½ ¼ ¾, `1/2`, `1 1/2`; Devanagari digits; `500gm` with no space.
  - English/Hindi unit spellings → KG/G/L/ML/PACKET/DOZEN/PCS. Units are mapped, never converted.
  - Leading serial numbers (`1.`, `2)`, `-`, `•`) are removed.
  - No quantity → 1 PCS, `confident=false`. A number without a unit → that many PCS, still confident. Several numbers, quantity out of range, or a name over 60 chars → `confident=false`.
  - Blank, punctuation-only and letter-less lines (e.g. a date) are dropped.
- `POST /api/parchi/scan` (CUSTOMER, multipart `image`):
  - Type is checked by magic bytes (JPEG/PNG/WebP), never by file name or header. 400 `UNSUPPORTED_IMAGE`.
  - Over 5 MB → 413 `IMAGE_TOO_LARGE` as JSON, both from the code check and from Spring's multipart limit. `server.tomcat.max-swallow-size: 20MB` makes sure the client actually receives it.
  - Saved as `uploads/<yyyy-MM>/<uuid>.<ext>`. Returns `{imageId, items}`.
- `POST /api/requests` takes optional `imageId`. It must be the customer's own (else 404 `IMAGE_NOT_FOUND`); it sets source PHOTO. Items still come from the body.
- `GET /api/requests/{id}/image` (own request) and `GET /api/store/requests/{id}/image`. Both stream the file with its real type, `Cache-Control: private, max-age=86400` and `nosniff`. Detail responses (customer and store) have `hasImage`.
- `ParchiCleanupJob`: daily at 03:30 (`app.parchi.cleanup-cron`). It deletes photos older than 24 h that no request uses: the row first (only if still unlinked), then the file. It logs the count.
- Added `/api/parchi/**` → CUSTOMER in SecurityConfig, and a JSON 400 for non-multipart uploads.
- PLAN.md updated: data model (`parchi_images`, `image_id`), API rows (scan, imageId, two image GETs), backend notes, folder list, V4.
- `samples/parchi.jpg` (a drawn parchi) and `samples/not-an-image.jpg` for requests.http.

**Files changed**
- new: `db/migration/V4__parchi_images.sql`
- new in `parchi/`: `ParchiProperties`, `ParchiImage`, `ParchiImageRepository`, `ImageType`, `ImageStorage`, `ParchiReader`, `MockParchiReader`, `ParchiLineParser`, `ParchiService`, `ParchiCleanupJob`, `ParchiController`, `dto/ScanResultDto`, `dto/ScannedItemDto`; updated `package-info.java`
- changed: `KiranaApplication` (`@EnableScheduling`), `SecurityConfig`, `GlobalExceptionHandler`, `application.yml`
- changed in `request/`: `OrderRequest` (`imageId`), `RequestService` (create with image, `customerImage`, `storeImage`), `RequestController`, `StoreInboxController`, `dto/CreateRequestRequest`, `dto/RequestDetailDto`, `dto/StoreRequestDetailDto`
- changed: `DevDataSeeder` (new constructor arg)
- tests: new `parchi/ParchiLineParserTest`, `parchi/ImageTypeTest`, `parchi/ParchiFlowIntegrationTest`, `src/test/resources/application-test.yml` (no delay, uploads under `target/`). Changed `IntegrationTestBase` (truncate) and `RequestServiceTest` (constructor).
- `requests.http` (6.1–6.14), `PLAN.md`, `samples/`

**Tests**
- `mvnw test`: 130 tests, 0 failures (65 new).
- Parser: 42 real-looking lines plus 9 dropped lines and edge cases.
- Magic-byte check rejects a `.txt` renamed to `.jpg`.
- Scan returns items and an imageId, and the file is saved under the uuid name.
- Someone else's imageId → 404.
- Non-targeted store → 404. Customer and targeted store can fetch the photo; after accept, the losing store can't.
- Cleanup removes only old unlinked photos.
- Live check on the dev DB with curl:
  - Scan of `samples/parchi.jpg` → 11 items. The fake .jpg → 400. A 6 MB upload → JSON 413 from real Tomcat.
  - Request #16 with source PHOTO; customer and Sharma got the photo byte-identical with the right headers; Bansal (not targeted) → 404.
  - #16 was cancelled afterwards.

**Different from PLAN.md / decisions**
- PLAN said `/parchi/scan` "saves nothing"; it now saves the photo and returns `imageId`, and unused photos are deleted after 24 h. PLAN.md updated (agreed).
- Store photo access is stricter than "any targeted store": only while the request is OPEN, or for the winning store. A store that lost, or a cancelled request, gets 404 `IMAGE_NOT_FOUND` and `hasImage:false`, because a parchi often has the customer's name or number on it (same rule as Phase 5 contacts). Easy to relax in `StoreRequestDetailDto.storeMaySeeImage`.
- The same imageId may be reused for several requests by the same customer (e.g. resend after cancel).
- `hasImage` is only on detail responses, not list rows.

**Left unfinished / open**
- No rate limit on scans; a customer can upload many 5 MB photos (they are deleted after 24 h if unused). Consider a per-user limit in Phase 7.
- Photos linked to requests are kept forever; there is no retention policy for them yet.
- Browser-side compression and the review screen are frontend work (not in this backend repo).

## Phase 7 — Privacy (one-time scan) + polish and hardening (2026-11-11)

**Built — Part A: parchi photos are never stored**
- `POST /api/parchi/scan` keeps the 5 MB limit and magic-byte check, runs reader + parser in memory, and returns `{items}` only (no `imageId`). The bytes are never written or logged. A reader failure is logged by exception class only.
- `POST /api/requests`: `fromPhoto` (default false) replaces `imageId` and sets source PHOTO/MANUAL.
- Removed:
  - `ParchiImage`, `ParchiImageRepository`, `ImageStorage`, `ParchiCleanupJob`
  - both `/image` endpoints, `hasImage`, `IMAGE_NOT_FOUND`
  - the upload/cleanup settings, `@EnableScheduling`, and the `uploads/` lines in `.gitignore`
- `ParchiReader`, `MockParchiReader`, `ParchiLineParser` unchanged.
- V5 drops `order_requests.image_id`, `image_path` and the `parchi_images` table.
- **Delete by hand:** `C:\Users\harsh singh\Desktop\Parchi Backend\uploads` (it still holds the Phase 6 photos in `2026-11/`).

**Built — Part B**
- **Pickup-code lock** (V5: `pickup_failed_attempts`, `pickup_locked_until`):
  - Wrong codes 1–5 → 400 `WRONG_PICKUP_CODE` with "N tries left". The 5th also locks the order for 15 min.
  - While locked, every try (even the right code) → 429 `TOO_MANY_ATTEMPTS` + `retryAfterSeconds` + `Retry-After`.
  - A correct code resets the counter; a try after the lock expires starts again at 0.
  - Runs under the existing row lock with `noRollbackFor = ApiException` so the count is saved.
- **Rate limits** (`common/RateLimiter`, in-memory sliding window; old keys swept lazily, no scheduler; settings in `app.rate-limits`):
  - Login: 5 failed attempts per phone per 15 min, then 429 even with the right password. A successful login clears the count. Unknown numbers count the same way.
  - Scan: 20 per customer per hour; every call counts.
  - Create request: 10 per customer per hour; only successful creates count.
  - Every 429 is `{code: TOO_MANY_ATTEMPTS, message, retryAfterSeconds}` plus `Retry-After`.
- **Pagination:** `/api/requests/mine` and `/api/store/requests` take `?page=0&size=20` (size 1–50, page ≥ 0, else 400) and return `{items, page, hasMore}`. Implemented with Spring Data `Slice` (fetches size+1, no count query). Item shapes unchanged.
- **Performance:**
  - Hibernate statistics on in tests. `QueryCountIntegrationTest` shows customer detail, mine, store inbox and store detail run the same number of statements with 1 item / 1 quote / 1 request as with 20 items / 5 quotes / 6 requests (at most 8 each). No N+1 needed fixing: the existing `default_batch_fetch_size: 50` already covers it.
  - EXPLAIN on 3,000 stores / 6,000 requests: the inbox uses the indexes, no seq scans.
  - Nearby/nearest scanned every open store. Now a lat/lng box pre-filter plus the partial index `ix_stores_open_lat_lng` (V5); the exact Haversine check still decides.
  - Also added `ix_qitem_request_item` (an unindexed FK).
  - gzip for JSON over 1 KB (checked live: `Content-Encoding: gzip`).
- **Logs:** `logback-spring.xml` masks phone numbers (`98XXXXXX10`), JWTs (`[jwt]`) and passwords in every message and stack trace, including DB error texts. Pickup codes are never logged. `LogMaskingTest` renders through the real layout.
- **Prod:**
  - `application-prod.yml`; `config/ProdSafetyCheck` runs as an EnvironmentPostProcessor before any bean or DB connection.
  - With `prod`, startup stops if `JWT_SECRET` is the default or under 32 chars, or `DB_PASSWORD` is the default. Only the setting names are printed. Checked live.
  - `DevDataSeeder` is `@Profile("dev & !prod")`.

**Files changed**
- new:
  - `V5__privacy_and_pickup_lock.sql`
  - `common/RateLimiter`, `common/RateLimitProperties`, `common/PageDto`, `config/ProdSafetyCheck`
  - `META-INF/spring.factories`, `application-prod.yml`, `logback-spring.xml`
- deleted: `parchi/ParchiImage`, `ParchiImageRepository`, `ImageStorage`, `ParchiCleanupJob`
- changed:
  - parchi: `ParchiService`, `ParchiController`, `ParchiProperties`, `dto/ScanResultDto`, `package-info`
  - request: `OrderRequest`, `OrderService`, `RequestService`, `RequestController`, `StoreInboxController`, `OrderRequestRepository`, `RequestTargetRepository`, `dto/CreateRequestRequest`, `dto/RequestDetailDto`, `dto/StoreRequestDetailDto`
  - auth/common/store/config: `AuthService`, `ApiError`, `ApiException`, `GlobalExceptionHandler`, `StoreRepository`, `DevDataSeeder`, `KiranaApplication`
  - config files: `application.yml`, `.gitignore`
- tests:
  - new: `HardeningIntegrationTest`, `QueryCountIntegrationTest`, `common/RateLimiterTest`, `common/LogMaskingTest`, `config/ProdSafetyCheckTest`
  - rewritten: `ParchiFlowIntegrationTest`
  - updated: `OrderServiceTest` (+2), `AuthServiceTest`, `RequestServiceTest`, `ImageTypeTest`, `IntegrationTestBase` (clears the rate limiter), `application-test.yml`; list assertions in Quote/Order/RequestFlow tests now use `$.items`
- docs: `requests.http` (Phase 6 blocks rewritten, Phase 7 blocks 7.1–7.21), `PLAN.md`

**Tests**
- `mvnw test`: 146 tests, 0 failures.
- New coverage:
  - scan writes no file and the `parchi_images` table is gone; `fromPhoto` → PHOTO/MANUAL
  - 6 wrong codes → 429, and 10 parallel wrong tries → exactly 5×400 + 5×429 with the counter at 5
  - login, scan and create limits; paging and its 400s
  - query counts constant; EXPLAIN uses indexes
  - log masking; prod check
- Live on the dev DB (V5 applied): every check above passed by curl, and the prod profile refused to start with defaults. The server log had no unmasked phone numbers or JWTs.

**Different from PLAN.md / decisions**
- The Phase 6 photo design is gone (product decision). PLAN.md now says "Privacy: parchi photos are never stored".
- `/requests/mine` and `/store/requests` return `{items, page, hasMore}` instead of a bare array (breaking for the frontend).
- Seed logins are listed in PLAN.md, since the seeder's phone table is now masked in the logs.
- The 5th wrong pickup code returns 400 (and locks); 429 starts from the 6th try.

**Left unfinished / open**
- Rate limits and failed-login counts are per instance and reset on restart; move to Redis or the DB before running more than one instance.
- Login is limited per phone only, not per IP, so one client can still try many different numbers.
- Live-check leftovers in the dev DB: one PHOTO request accepted by Sharma, with its pickup code locked for 15 minutes.
- PLAN.md open question from Phase 4 (stores dismissing requests, resending stale ones) is still undecided.
