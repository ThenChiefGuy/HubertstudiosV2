# HubertStudios License/Folia Fix Report

## Additional verification pass

The first bundle was too optimistic. This pass found and fixed more issues.

## Backend fixes

- Preserved credentialed CORS for Vercel -> Cloudflare Worker requests.
- Preserved multiple `Set-Cookie` headers for auth verify.
- Kept `SameSite=None; Secure` cookies for cross-origin dashboard/backend deployment.
- Fixed modern signed validation response fields.
- Fixed a legacy-mode signature bug: legacy products now receive the legacy response shape only. The previous response included modern-only fields (`serverFingerprint`, `blocked`, `code`) even when the signature payload was legacy, which would make clients verify the wrong string and reject valid legacy licenses.
- Confirmed `backend/src/index.js` and `backend/src/index.combined.js` pass `node --check`.

## Frontend fixes

- Removed dashboard dependency on `lib/mock-data.ts` for real pages.
- Wired pages to the Worker API through `lib/api.ts`:
  - overview
  - plugins
  - licenses
  - builds
  - server bans
  - active servers
  - audit log
  - settings
- Replaced placeholder dialog handlers with real create/update/delete API calls.
- Added live loading states and error toasts.
- Added missing `public/placeholder.svg` so plugin cards do not reference a missing asset.
- Updated `frontend/WIRING_STATUS.md` to reflect the real API wiring.

## Universal license client / OrbitalStrike

- `license-java-client/` remains the drop-in Bukkit/Paper/Folia-oriented license client.
- OrbitalStrike includes `com.hubertstudios.license.License` and `LicenseGate`.
- OrbitalStrike reads `plugins/OrbitalStrike/license.yml` for:
  - license key
  - Worker URL
  - public key PEM
  - recheck interval
  - optional server identifiers
- `License.java` compiled with `javac`.
- `LicenseGate.java` compiled against local Bukkit stubs.

## CoreGuard fixes

- CoreGuard is marked `folia-supported: true`.
- Added `SchedulerUtil` as a Bukkit/Paper/Folia scheduler bridge.
- CoreGuard license checks now read Worker URL and public key PEM from `plugins/CoreGuard/license.yml` instead of hardcoded Java constants.
- Updated `CoreGuard/src/main/resources/license.yml` to match the universal license template.
- CoreGuard license classes compiled against local Bukkit stubs:
  - `SchedulerUtil`
  - `LicenseManager`
  - `RuntimeLicenseGate`

## Not fully verified here

- Full Maven builds: Maven is not installed in this environment.
- Frontend production build: node dependencies are not installed in this environment.
- Live Cloudflare Worker deployment.
- Live Vercel dashboard deployment.
- Runtime tests on real Paper and Folia servers.

## Required live test order

1. Deploy Worker with `DASHBOARD_ORIGIN` set to the exact Vercel dashboard origin.
2. Run all D1 migrations, including `0003_sighting_players.sql`.
3. Deploy frontend with `NEXT_PUBLIC_API_BASE_URL` set to the Worker URL.
4. Test browser login and email verification.
5. Create products exactly named `OrbitalStrike` and `CoreGuard`.
6. Create licenses for both products.
7. If hash validation is enabled, build each plugin JAR, calculate SHA-256, and register each hash under Builds.
8. Start a Paper test server.
9. Start a Folia test server.
10. Confirm both plugins validate and remain enabled.
