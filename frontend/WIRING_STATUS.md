# Frontend wiring status

The dashboard pages are now wired to the Cloudflare Worker API through `lib/api.ts`.

## Wired pages

- Login: `POST /api/auth/login`
- Verify code: `POST /api/auth/verify`, then `GET /api/auth/session`
- Overview: `GET /api/admin/overview`, `GET /api/admin/audit-log`, `GET /api/admin/bans`
- Plugins: list/create/update/delete through `/api/admin/products`
- Licenses: list/create/update/delete through `/api/admin/licenses`
- Builds: list/create/update/delete through `/api/admin/builds`
- Server bans: list/create/update/delete through `/api/admin/bans`
- Active servers: `GET /api/admin/active-servers`
- Audit log: `GET /api/admin/audit-log`
- Settings: `GET/PUT /api/admin/settings`

## Required environment variables

Frontend/Vercel:

```env
NEXT_PUBLIC_API_BASE_URL=https://your-cloudflare-worker-url
NEXT_PUBLIC_TURNSTILE_SITE_KEY=your-public-turnstile-site-key
```

Backend/Cloudflare Worker secrets:

```env
DASHBOARD_ORIGIN=https://your-vercel-dashboard-url
TURNSTILE_SECRET_KEY=your-secret-turnstile-key
ADMIN_EMAIL=...
ADMIN_PASSWORD_HASH=...
ADMIN_PASSWORD_SALT=...
SESSION_SECRET=...
PRIVATE_KEY=...
EMAIL_API_KEY=...
EMAIL_FROM=...
```

## Still requiring live verification

- Browser login on the deployed Vercel domain against the deployed Worker domain.
- Admin create/edit/delete actions against a real D1 database.
- Turnstile token verification on the configured production domain.
