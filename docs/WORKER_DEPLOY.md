# Cloudflare Worker Deployment

## Files

| File | Purpose |
|------|---------|
| `backend/src/index.combined.js` | **Single-file deploy** — paste this into the Cloudflare dashboard Worker editor |
| `backend/src/index.js` | Entry point for multi-file/wrangler CLI deploy |
| `backend/src/lib/` | Shared libraries (auth, crypto, db, email, http, etc.) |
| `backend/src/routes/` | Route handlers (licenses, builds, bans, products, validate, etc.) |
| `backend/wrangler.toml` | Reference config — lists required bindings and secrets |
| `backend/migrations/` | D1 SQL migrations — run in order: 0001 → 0002 → 0003 |
| `backend/scripts/setup-tool.html` | Browser tool: generate password hash, ECDSA key pair |

## Bindings required

| Binding | Type | Variable name in code |
|---------|------|-----------------------|
| D1 database | D1 | `env.DB` |
| KV namespace | KV | `env.KV` |

## Secrets required

| Secret | Description |
|--------|-------------|
| `ADMIN_EMAIL` | Admin login email |
| `ADMIN_PASSWORD_HASH` | Hex string from setup-tool.html |
| `ADMIN_PASSWORD_SALT` | Hex string from setup-tool.html |
| `SESSION_SECRET` | 32+ random bytes hex (`openssl rand -hex 32`) |
| `PRIVATE_KEY` | ECDSA P-256 private key PEM from setup-tool.html |
| `EMAIL_API_KEY` | Resend or Postmark API key |
| `EMAIL_FROM` | Sender email address |
| `TURNSTILE_SECRET_KEY` | From Cloudflare Turnstile widget |
| `DASHBOARD_ORIGIN` | `https://hubert-studios.vercel.app` — exact, no trailing slash |

## API routes

### Public (no auth)
| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/validate` | Plugin license validation |
| POST | `/api/auth/login` | Admin login step 1 (email + Turnstile) |
| POST | `/api/auth/verify` | Admin login step 2 (email code) |
| POST | `/api/auth/logout` | Clear session cookie |
| GET  | `/api/auth/session` | Get session + CSRF token |
| GET  | `/health` | Health check |

### Admin (session cookie + CSRF token required)
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/admin/overview` | Dashboard stats |
| GET/POST | `/api/admin/products` | List / create plugins |
| PUT/DELETE | `/api/admin/products/:id` | Update / delete plugin |
| GET/POST | `/api/admin/licenses` | List / create licenses |
| PUT/DELETE | `/api/admin/licenses/:id` | Update / delete license |
| GET/POST | `/api/admin/builds` | List / create builds |
| PUT/DELETE | `/api/admin/builds/:id` | Update / delete build |
| GET/POST | `/api/admin/bans` | List / create bans |
| PUT/DELETE | `/api/admin/bans/:id` | Update / delete ban |
| GET | `/api/admin/active-servers` | Recently seen servers |
| GET | `/api/admin/audit-log` | Last 200 audit events |
| GET/PUT | `/api/admin/settings` | Global settings |

## CORS policy

The Worker sets `Access-Control-Allow-Origin` to the exact value of `DASHBOARD_ORIGIN` (set in secrets).
All admin/auth routes require `credentials: "include"` on the client side.
The `X-CSRF-Token` header is required on all mutating admin requests.

## Signed validation responses

Validation responses are ECDSA-P256 signed. There are two signature formats:

- **Modern** (default): `valid:plugin:licenseFingerprint:hash:serverFingerprint:blocked:code:timestamp`
- **Legacy** (products marked `mode = "legacy"`): `valid:plugin:licenseFingerprint:hash:timestamp`

The Java client automatically detects which format applies based on the `serverFingerprint`/`blocked`/`code` fields being present in the response.
