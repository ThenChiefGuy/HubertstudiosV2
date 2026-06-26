# Test Checklist

## Auth flow

- [ ] Login with correct email + password + valid Turnstile token → returns `{ ok: true, step: "verify" }`, sets challenge cookie, sends email.
- [ ] Login with wrong password → generic "Invalid credentials." error (same message as wrong email).
- [ ] Login with wrong email → same generic error.
- [ ] Login with missing/invalid Turnstile token → rejected before password is even checked.
- [ ] 5 wrong password attempts from one IP → 6th attempt (even with correct password) returns 429 "Too many attempts."
- [ ] After the 15-minute block window, login works again.
- [ ] Verify with correct 6-digit code → sets session cookie, clears challenge cookie, returns `{ ok: true }`.
- [ ] Verify with wrong code → generic error, does not reveal whether the code was close.
- [ ] 5 wrong codes from one IP → blocked for 15 minutes.
- [ ] Verify with an expired (>10 min old) or already-used challenge → rejected, must restart from login.
- [ ] Session cookie has `HttpOnly`, `Secure`, `SameSite=None` flags and the response includes `Access-Control-Allow-Credentials: true` for the exact dashboard origin (inspect via browser devtools, not just code review).
- [ ] Session expires after 8 hours — `GET /api/auth/session` returns 401 once expired.
- [ ] Logout clears the session cookie and the session stops working immediately (not just client-side).
- [ ] Any `/api/admin/*` request without a session cookie → 401.
- [ ] Any mutating `/api/admin/*` request (`POST`/`PUT`/`DELETE`) with a valid session but missing/wrong `X-CSRF-Token` header → 403.
- [ ] `GET /api/admin/*` requests work without a CSRF header (CSRF only required on mutations).

## /api/validate — happy path

- [ ] Valid plugin + valid license + (if hash required) valid hash + no bans → `valid: true`, signature present, `reason: "OK"`.
- [ ] Response signature verifies against the public key using the exact signature format for the plugin's mode (legacy vs modern).
- [ ] A successful call upserts a row visible on the "Active Servers" admin page.

## /api/validate — rejections (each must return `valid: false` with a distinct, correct reason)

- [ ] Malformed JSON body.
- [ ] Missing `plugin` field.
- [ ] Plugin not in `ALLOWED_PRODUCTS`.
- [ ] `licenseFingerprint` doesn't match `sha256(license)`.
- [ ] Product globally disabled (`<product>:DISABLED` = `"true"`).
- [ ] License key doesn't exist.
- [ ] License exists but `status = revoked`.
- [ ] License exists but `expires_at` is in the past.
- [ ] License has an `allowedServers` list and none of the submitted identifiers match.
- [ ] License has a `blockedServers` list and a submitted identifier matches.
- [ ] Server identifier matches a plugin-specific ban.
- [ ] Server identifier matches a global ban.
- [ ] Hash required, but `hash` field missing or not 64-char hex.
- [ ] Hash required, hash well-formed, but not a registered build for this plugin.
- [ ] Hash required, hash registered, but `active = false` on that build row.
- [ ] Confirm `server.bindIp = "0.0.0.0"` is excluded from identifier matching (i.e. a wildcard bind never itself satisfies an allow/block list).
- [ ] Confirm an MOTD-like field, if ever added to the request body, is never read for ban matching (there is no `motd` reference anywhere in `routes/validate.js`).

## /api/validate — legacy vs modern

- [ ] A plugin name present in `LEGACY_PRODUCTS` is read from `LICENSE:<key>`, not `<product>.license:<key>`.
- [ ] A plugin name absent from `LEGACY_PRODUCTS` is read from `<product>.license:<key>`.
- [ ] Legacy plugin gets the legacy signature format (`valid:plugin:licenseFingerprint:hash:timestamp`); modern gets the modern format. Confirm by re-deriving both signatures locally and checking which one a legacy product's response matches.

## /api/validate — KV/D1 consistency

- [ ] After creating a license/build/ban in the dashboard, confirm the corresponding KV key exists (via the Workers & Pages → KV → your namespace → browse, in the dashboard) immediately, without needing a second save.
- [ ] Manually delete a KV key (simulating cache loss/lag) for an existing license via the dashboard KV browser, and confirm `/api/validate` still succeeds via the D1 fallback path.
- [ ] Deleting a plugin in the dashboard removes its `ALLOWED_PRODUCTS`/`LEGACY_PRODUCTS` membership and its `:DISABLED`/`:REQUIRE_HASH` KV keys.

## Admin CRUD

- [ ] Create/edit/delete a plugin; confirm dashboard list reflects it without a page reload (if frontend wiring is in place) or via `GET /api/admin/products`.
- [ ] Uploading a plugin image (base64 data URL) is stored in `products.image_data` and the returned `image` field renders directly in an `<img>` tag — no separate image-serving endpoint exists or is needed.
- [ ] Replacing a plugin's image simply overwrites `image_data` in the same row — there's no orphaned-object cleanup concern since there's no separate storage layer.
- [ ] Uploading an image over the size cap (~1.5MB raw / ~2MB base64) is rejected with a clear error rather than silently truncated or causing a D1 write failure.
- [ ] Create/edit/delete a license; confirm `key` is unique (duplicate key creation returns 409).
- [ ] Create/edit/delete a build; confirm duplicate `(productId, hash)` pair returns 409.
- [ ] Create/edit/delete a global ban (no `productId`) and a plugin-specific ban; confirm each writes to the correct KV key shape.
- [ ] Every create/update/delete above produces exactly one new row in `admin_audit_log`, visible via `GET /api/admin/audit-log`.

## Security headers

- [ ] Every response (success and error) includes `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Content-Security-Policy`, `Strict-Transport-Security`.
- [ ] No response anywhere includes the `PRIVATE_KEY`, the password hash/salt, `SESSION_SECRET`, or any third-party API key.

## Negative/abuse testing

- [ ] Replaying an old, expired `/api/validate` success response against the Java client does not pass verification once the client checks the `timestamp` freshness window (confirm this check exists in `LicenseGate.java`/`License.java`).
- [ ] Submitting an extremely large request body to `/api/validate` doesn't crash the Worker (Workers have a request size limit, but confirm graceful JSON-parse failure rather than an unhandled exception).
- [ ] Hitting `/api/admin/*` routes with a forged/garbage session cookie value returns 401, not a 500.

## Deployment-specific (dashboard-only, no CLI)

- [ ] All 14 files under `src/` are present in the Quick Edit file tree exactly as listed in README.md — a missing file causes an import error at deploy time, which the dashboard editor should surface as a save/deploy error.
- [ ] D1 binding variable name is exactly `DB` and KV binding variable name is exactly `KV` (case-sensitive, must match what the code expects).
- [ ] All 8 secrets are set as type **Secret**, not **Text** — Text variables are visible in plaintext in the dashboard and in `wrangler.toml` exports, which defeats the purpose for things like `PRIVATE_KEY`.
