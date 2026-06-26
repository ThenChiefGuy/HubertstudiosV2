# HubertStudios License System — Replit Workspace

## Project overview

This is an organized source-code workspace for the HubertStudios plugin license system. It is **not** a single deployable application — it contains four separate deployment targets:

| Component | Deploy target |
|-----------|--------------|
| `backend/` | Cloudflare Worker at `https://api.gg69nah.workers.dev` |
| `frontend/` | Vercel (Next.js) at `https://hubert-studios.vercel.app` |
| `plugins/OrbitalStrike/` | Paper / Folia server (Maven build) |
| `plugins/CoreGuard/` | Paper / Folia server (Maven build) |
| `license-java-client/` | Copied into any Bukkit/Paper/Folia plugin |

The Replit preview shows a workspace overview page served by `server/index.js`.

## How to use this workspace

- **Read the docs** in `docs/` for full deployment instructions.
- **Edit Worker code** in `backend/src/` — validate with `node --check backend/src/index.combined.js`.
- **Edit the dashboard** in `frontend/` — deploy to Vercel.
- **Edit plugin code** in `plugins/OrbitalStrike/` and `plugins/CoreGuard/` — build with Maven.
- **Use the license client** from `license-java-client/` — copy into any plugin.

## Key URLs

- Worker: https://api.gg69nah.workers.dev
- Dashboard: https://hubert-studios.vercel.app/login

## User preferences

- Keep workspace organized by component directory (backend, frontend, plugins, license-java-client, docs).
- No mock data — all dashboard pages call the real Worker API.
- All Java plugins must be Folia-safe (no direct Bukkit scheduler calls from off-thread).
