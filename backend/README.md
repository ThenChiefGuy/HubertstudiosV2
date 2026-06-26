# HubertStudios License Manager — Backend (Cloudflare Worker)

A single Cloudflare Worker that serves:

- `POST /api/validate` — the plugin license validation endpoint your Java plugins call.
- `POST /api/auth/login`, `/verify`, `/logout`, `GET /api/auth/session` — admin authentication.
- `GET/POST/PUT/DELETE /api/admin/*` — admin CRUD for plugins, licenses, builds, and server bans (session + CSRF required).

D1 is the source of truth for everything. KV is a read-through cache that exists purely so `/api/validate` never has to do more than a couple of KV reads on the hot path. Every admin write goes to D1 first, then mirrors the relevant rows into KV — never the other way around.

**This guide assumes you're doing everything from the Cloudflare dashboard, on a phone, with no command line.** There is no `wrangler` CLI step anywhere below. The only "CLI-shaped" thing in this whole repo is a single HTML file (`scripts/setup-tool.html`) that runs entirely in your phone's browser.

**No R2.** Plugin images are stored as base64 data URLs directly in the D1 `products` table and returned inline in the API response — no object storage bucket, no separate binding, no extra account to set up.

## 1. Create the D1 database (you've already done this)

You mentioned you already created the D1 database and ran the tables. Good — just double check the table list matches `migrations/0001_init.sql`. If your `products` table has an `image_key` column instead of `image_data`, run `migrations/0002_no_r2_images.sql` once (D1 dashboard → your database → **Console** tab → paste the `ALTER TABLE` statement → Execute). The Worker code only ever reads/writes `image_data`.

If you ever need to redo it from scratch: **Workers & Pages → D1 → Create database** → name it (e.g. `hubertstudios-license-db`) → open it → **Console** tab → paste the entire contents of `migrations/0001_init.sql` → Execute.

## 2. Create the KV namespace

**Workers & Pages → KV → Create namespace** → name it (e.g. `hubertstudios-license-kv`). Nothing to seed manually — the Worker populates it automatically the first time you save a plugin/license/build/ban in the dashboard.

## 3. Create the Worker

**Workers & Pages → Create application → Create Worker.** Give it a name (e.g. `hubertstudios-license-manager`), then **Deploy** the default "Hello World" template first — you'll replace its contents next.

## 4. Add your source files in Quick Edit

Open the Worker you just created → **Edit code** (this opens the VS Code–for-Web based editor, which supports multiple files/folders — perfect for this on a phone).

Recreate this exact file tree by tapping the "New File" / "New Folder" icons in the file explorer panel and pasting in the matching content from this repo:

```
src/
  index.js
  lib/
    auth.js
    crypto.js
    db.js
    email.js
    http.js
    kvKeys.js
    middleware.js
    sync.js
    turnstile.js
  routes/
    auth.js
    bans.js
    builds.js
    licenses.js
    misc.js
    products.js
    validate.js
```

Set `src/index.js` as the entry point if the editor asks (it should detect this automatically since it's the default `main` Cloudflare looks for).

Tap **Save and deploy** once all 14 files are in place.

## 5. Add the D1 and KV bindings

Worker → **Settings → Variables and Bindings → Add binding**:

- Type **D1 database** → Variable name `DB` → select the database from step 1.
- Type **KV namespace** → Variable name `KV` → select the namespace from step 2.

These exact variable names (`DB`, `KV`) matter — the code refers to `env.DB` and `env.KV`.

## 6. Generate your secrets using the setup tool

Open `scripts/setup-tool.html` on your phone (download it from this repo and open it directly in your browser — it needs no server, no install, and makes no network requests). It will generate:

- An ECDSA P-256 keypair (private key for the Worker, public key for your Java plugins)
- Your admin password hash + salt (PBKDF2-SHA256, 210,000 iterations)
- A random `SESSION_SECRET`

Keep that page open in one tab while you copy values into the dashboard in the next step.

## 7. Set the Worker secrets

Worker → **Settings → Variables and Bindings → Add** → for each one, choose type **Secret** (not "Text" — Secret values are encrypted and hidden after saving):

| Secret | Where it comes from |
|---|---|
| `ADMIN_EMAIL` | Your own admin email address — just type it in |
| `ADMIN_PASSWORD_HASH` | From the setup tool, step 6 |
| `ADMIN_PASSWORD_SALT` | From the setup tool, step 6 |
| `SESSION_SECRET` | From the setup tool, step 6 |
| `PRIVATE_KEY` | From the setup tool, step 6 (the full PEM block, including `-----BEGIN/END-----` lines) |
| `EMAIL_API_KEY` | See step 8 |
| `EMAIL_FROM` | See step 8, e.g. `"License Manager <noreply@yourdomain.com>"` |
| `TURNSTILE_SECRET_KEY` | See step 9 |

Keep the **public** key from the setup tool — you'll need it later for `License.java`, not as a Worker secret.

## 8. Set up email (for the 2FA verification code)

This Worker uses [Resend](https://resend.com) to email your admin login verification code. Resend has a free tier and the whole setup is a phone-friendly web signup:

1. Sign up at resend.com.
2. Verify a sending domain (or use their test domain while testing).
3. Create an API key → paste it as the `EMAIL_API_KEY` secret.
4. Set `EMAIL_FROM` to an address on your verified domain.

If you'd rather use a different provider, only `src/lib/email.js` needs to change — nothing else in the Worker depends on the provider's shape.

## 9. Set up Cloudflare Turnstile

**Turnstile** (in the Cloudflare dashboard sidebar) → **Add site** → enter your dashboard's domain → choose the **Managed** challenge type → create. Copy the **Secret Key** into the `TURNSTILE_SECRET_KEY` Worker secret. You'll also need the **Site Key** later when wiring up the actual Turnstile widget in the dashboard frontend's login page.

## 10. Register your first plugin

Once the Worker is deployed with all bindings and secrets in place, the easiest way to create your first plugin/license/build is straight from the dashboard frontend once it's deployed and pointed at this Worker (see the dashboard's own setup). The admin UI handles creating products, licenses, builds, and bans — there's no manual KV seeding required; every save keeps D1 and KV in sync automatically.

## 11. Point the dashboard frontend at this Worker

If the dashboard is deployed separately (e.g. Cloudflare Pages), set its API base URL to this Worker's URL, and make sure both are served from the credentialed CORS configured: set Worker secret `DASHBOARD_ORIGIN` to the exact dashboard origin, and set frontend `NEXT_PUBLIC_API_BASE_URL` to the Worker URL. Cookies use `SameSite=None; Secure` for Vercel → Worker requests.

## 12. Rotating the signing keypair

**If you ever rotate the ECDSA keypair, every already-shipped plugin build with the old public key baked in will start failing signature verification.** Roll out a new plugin build with the new public key before — or atomically with — rotating the Worker's `PRIVATE_KEY` secret.

## Notes on what this backend deliberately does NOT do

- No customer/per-user licensing. Licenses are global per plugin, by design — this matches the dashboard frontend exactly.
- No R2, no object storage account of any kind. Plugin images are small base64 blobs stored directly in D1.
- No destructive auto-actions. Nothing in this backend deletes data without an explicit admin request; expired licenses/bans simply stop validating, they aren't purged.
- No Cloudflare API token is ever sent to or stored in the browser. The dashboard only ever talks to this Worker's own `/api/admin/*` routes.
