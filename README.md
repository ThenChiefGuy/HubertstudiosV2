# HubertStudios License System

Organized workspace for the HubertStudios plugin license system.

| Component | Location | Target deployment |
|-----------|----------|-------------------|
| Cloudflare Worker (backend) | `backend/` | https://api.gg69nah.workers.dev |
| Next.js dashboard (frontend) | `frontend/` | https://hubert-studios.vercel.app |
| Universal Java license client | `license-java-client/` | Copy into any Bukkit/Paper/Folia plugin |
| OrbitalStrike plugin | `plugins/OrbitalStrike/` | Paper / Folia server |
| CoreGuard plugin | `plugins/CoreGuard/` | Paper / Folia server |
| Setup documentation | `docs/` | — |

## Quick links

- [Full setup guide](docs/SETUP.md)
- [Cloudflare Worker deploy](docs/WORKER_DEPLOY.md)
- [Frontend (Vercel) deploy](docs/FRONTEND_DEPLOY.md)
- [Java plugin integration](docs/JAVA_CLIENT.md)
- [OrbitalStrike plugin](plugins/OrbitalStrike/README.md)
- [CoreGuard plugin](plugins/CoreGuard/README.md)
- [Backend API reference](backend/README.md)
- [Fix report](FIX_REPORT.md)

## Architecture

```
Browser (Vercel)                Cloudflare Worker
┌─────────────────────┐         ┌────────────────────────────┐
│ Next.js dashboard   │ ←CORS→  │ License Manager Worker     │
│ /login              │         │ /api/auth/*                │
│ /verify             │         │ /api/admin/*               │
│ /overview           │         │ /api/validate              │
│ /plugins …          │         │                            │
└─────────────────────┘         │  env.DB  → Cloudflare D1   │
                                │  env.KV  → Cloudflare KV   │
Java Plugins (Paper/Folia)      └────────────────────────────┘
┌─────────────────────┐                   ↑
│ OrbitalStrike       │ ──POST /api/validate──┘
│ CoreGuard           │
│ (any plugin)        │
└─────────────────────┘
```

## Syntax check status

All 17 Cloudflare Worker JS files pass `node --check` with zero errors.
Frontend TypeScript source is ready for `pnpm build` on Vercel.
Java sources require Maven 3.8+ and Java 17+ to compile.
