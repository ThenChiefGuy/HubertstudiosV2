# Frontend Dashboard Deployment (Vercel)

## Technology stack

- **Next.js 16** (App Router)
- **Tailwind CSS v4**
- **shadcn/ui** components
- **TypeScript**
- Package manager: **pnpm**

## Local development

```bash
cd frontend
cp .env.example .env.local
# Edit .env.local — set NEXT_PUBLIC_API_BASE_URL and NEXT_PUBLIC_TURNSTILE_SITE_KEY
pnpm install
pnpm dev
```

The dashboard runs at `http://localhost:3000`.

> **Note:** cookies set by the Worker use `SameSite=None; Secure`, which requires HTTPS. For local dev against the live Worker, you may need to use a Vercel preview deploy or an HTTPS tunnel. Alternatively, the Worker's CORS/cookie settings allow same-origin use if both are on the same host.

## Vercel deployment

1. Connect your GitHub repo (or import the `frontend/` folder) to a new Vercel project.
2. Set the **Root Directory** to `frontend/`.
3. Add these **Environment Variables**:

| Variable | Value |
|----------|-------|
| `NEXT_PUBLIC_API_BASE_URL` | `https://api.gg69nah.workers.dev` |
| `NEXT_PUBLIC_TURNSTILE_SITE_KEY` | Your Cloudflare Turnstile site key |

4. Deploy. Live at `https://hubert-studios.vercel.app`.

## Pages and their API calls

| Page | Route | Worker endpoints called |
|------|-------|------------------------|
| Login | `/login` | `POST /api/auth/login` |
| Verify | `/verify` | `POST /api/auth/verify` |
| Overview | `/overview` | `GET /api/admin/overview` |
| Plugins | `/plugins` | `GET/POST/PUT/DELETE /api/admin/products` |
| Licenses | `/licenses` | `GET/POST/PUT/DELETE /api/admin/licenses` |
| Builds | `/builds` | `GET/POST/PUT/DELETE /api/admin/builds` |
| Server Bans | `/server-bans` | `GET/POST/PUT/DELETE /api/admin/bans` |
| Active Servers | `/active-servers` | `GET /api/admin/active-servers` |
| Audit Log | `/audit-log` | `GET /api/admin/audit-log` |
| Settings | `/settings` | `GET/PUT /api/admin/settings` |

## Auth flow

1. User submits email + password + Turnstile token → `POST /api/auth/login`.
2. Worker sends an email code, returns `{ ok: true, step: "verify" }`.
3. Browser navigates to `/verify`.
4. User enters the code → `POST /api/auth/verify`.
5. Worker sets an HttpOnly session cookie (`SameSite=None; Secure`).
6. Browser fetches `GET /api/auth/session` to get the CSRF token stored in memory.
7. All subsequent mutating requests include `X-CSRF-Token: <csrfToken>` header.

## Key files

| File | Purpose |
|------|---------|
| `frontend/lib/api.ts` | All Worker API calls — single source of truth |
| `frontend/components/dashboard/auth-guard.tsx` | Redirects unauthenticated users to /login |
| `frontend/app/(dashboard)/layout.tsx` | Shared dashboard layout with sidebar |
| `frontend/components/dashboard/sidebar.tsx` | Navigation sidebar |
| `frontend/app/login/page.tsx` | Login form with Turnstile |
| `frontend/app/verify/page.tsx` | Email code verification |
