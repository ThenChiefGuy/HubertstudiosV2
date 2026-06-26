# HubertStudios License System — Full Setup Guide

## Prerequisites

| Tool | Required for |
|------|-------------|
| Cloudflare account | Worker, D1, KV, Turnstile |
| Vercel account | Next.js dashboard |
| Node.js 18+ | Local frontend dev |
| pnpm | Frontend package manager |
| Java 17+, Maven 3.8+ | Building Java plugins |

---

## Step 1 — Cloudflare: create D1 database

1. Cloudflare dashboard → **Workers & Pages** → **D1** → **Create database**.
2. Name it `hubertstudios-license-db`. Copy the **Database ID**.
3. Open the database → **Console** tab.
4. Paste and run each migration file in order:
   - `backend/migrations/0001_init.sql`
   - `backend/migrations/0002_no_r2_images.sql`
   - `backend/migrations/0003_sighting_players.sql`

---

## Step 2 — Cloudflare: create KV namespace

1. **Workers & Pages** → **KV** → **Create namespace**.
2. Name it `hubertstudios-license-kv`. Copy the **Namespace ID**.

---

## Step 3 — Cloudflare: create the Worker

1. **Workers & Pages** → **Create** → **Create Worker**.
2. Name it `hubertstudios-license-manager`.
3. After creation, go to **Settings** → **Bindings**:
   - Add **D1 database** binding → Variable name: `DB` → select your D1 db.
   - Add **KV namespace** binding → Variable name: `KV` → select your KV namespace.
4. Upload the worker code: edit the worker and paste the contents of `backend/src/index.combined.js` (single-file deploy).
5. Go to **Settings** → **Variables and Bindings** → add the following **Secrets** (use Secret type, not plain text):

| Secret name | Value |
|-------------|-------|
| `ADMIN_EMAIL` | Your admin login email |
| `ADMIN_PASSWORD_HASH` | See step 3a below |
| `ADMIN_PASSWORD_SALT` | See step 3a below |
| `SESSION_SECRET` | 32+ random bytes, hex-encoded (use `openssl rand -hex 32`) |
| `PRIVATE_KEY` | ECDSA P-256 private key PEM — from setup-tool.html |
| `EMAIL_API_KEY` | Your email provider API key (Resend / Postmark) |
| `EMAIL_FROM` | Sender address, e.g. `noreply@yourdomain.com` |
| `TURNSTILE_SECRET_KEY` | From Cloudflare Turnstile widget settings |
| `DASHBOARD_ORIGIN` | `https://hubert-studios.vercel.app` (exact, no trailing slash) |

### Step 3a — generate password hash/salt

Open `backend/scripts/setup-tool.html` in a browser. Use the **Password hash** section to generate `ADMIN_PASSWORD_HASH` and `ADMIN_PASSWORD_SALT` for your chosen password.

The same tool's **Key generation** section produces the ECDSA `PRIVATE_KEY` PEM and the matching public key PEM you paste into `license.yml` for each plugin.

---

## Step 4 — Cloudflare: create Turnstile widget

1. Cloudflare dashboard → **Turnstile** → **Add site**.
2. Domain: `hubert-studios.vercel.app`.
3. Copy the **Site Key** (public) — goes into Vercel env as `NEXT_PUBLIC_TURNSTILE_SITE_KEY`.
4. Copy the **Secret Key** — goes into the Worker secret `TURNSTILE_SECRET_KEY`.

---

## Step 5 — Vercel: deploy the dashboard

1. Push (or import) the `frontend/` directory to Vercel as a Next.js project.
2. Set these **Environment Variables** in Vercel project settings:

| Variable | Value |
|----------|-------|
| `NEXT_PUBLIC_API_BASE_URL` | `https://api.gg69nah.workers.dev` |
| `NEXT_PUBLIC_TURNSTILE_SITE_KEY` | From step 4 |

3. Deploy. The dashboard will be live at `https://hubert-studios.vercel.app`.

---

## Step 6 — Verify CORS end-to-end

1. Open `https://hubert-studios.vercel.app/login`.
2. Log in with your admin credentials.
3. Confirm the email code arrives and `/verify` works.
4. Confirm the overview/dashboard loads real data from the Worker.

Common pitfall: the Worker's `DASHBOARD_ORIGIN` secret must match the Vercel URL **exactly** (no trailing slash, correct scheme).

---

## Step 7 — Configure and deploy plugins

See [JAVA_CLIENT.md](JAVA_CLIENT.md) for the universal license client.
See [plugins/OrbitalStrike/README.md](../plugins/OrbitalStrike/README.md) and [plugins/CoreGuard/README.md](../plugins/CoreGuard/README.md) for plugin-specific steps.

Quick checklist per plugin:
1. Build the plugin JAR with Maven: `mvn package -f plugins/<PluginName>/pom.xml`
2. Calculate the JAR SHA-256: `sha256sum target/<plugin>.jar`
3. In the dashboard → **Builds** → register the hash for the matching plugin/version.
4. Edit `plugins/<PluginName>/license.yml` with your license key, Worker URL, and public key PEM.
5. Drop the JAR into your Paper/Folia server's `plugins/` folder.
6. Start the server — the plugin will validate on enable.
