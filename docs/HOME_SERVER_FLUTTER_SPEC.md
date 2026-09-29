# FinPulse home-server flagship plan

## Decision

FinPulse runs from the current Windows PC as a 24/7 home server. No VPS, no SaaS,
no Tailscale for the first target. The phone apps connect to this server.

- Server: current PC, FastAPI, AI/OCR/Ollama/T-Bank import.
- Android: Flutter APK.
- iPhone: Flutter Web/PWA first, because native iOS builds require macOS/Xcode.
- Native iOS source stays possible in Flutter, but `.ipa` is not part of the first
  deliverable without a Mac or cloud iOS builder.

## Constraints

- Heavy AI/OCR stays on the PC.
- Phone handles UI, cache, drafts, simple summaries, debt simulations and upload
  preparation.
- Android can later parse bank SMS/push notifications with user permission.
- iPhone cannot read T-Bank SMS/push from other apps, so it uses PDF/manual/PWA upload.
- Internet access from outside home requires direct reachability to the PC:
  public IPv4 or router port forwarding/DDNS/HTTPS. If the provider blocks inbound
  access or uses CG-NAT, this exact "only current PC, no external server" model can
  work only inside the home network.

## Version 0.3 target

Make a usable home-server app for two people.

1. Flutter shell
   - Shared Android/Web UI.
   - Login/register.
   - Server URL screen.
   - Pulse screen.
   - Add transaction.
   - Bank PDF upload placeholder.
   - T-Bank notification text import.

2. Backend API
   - `GET /api/v1/server/config`
   - `GET /api/v1/pulse/today`
   - `GET /api/v1/workspace`
   - `PUT /api/v1/workspace`
   - `POST /api/v1/import/tbank-notification`

3. Couple settings
   - Modes: `solo`, `couple`.
   - Default visibility: `private`, `shared`, `amount_only`.
   - Default split: `none`, `equal`, `percent`, `manual`.
   - Partner display name.

4. T-Bank first pass
   - PDF stays through existing bank statement import.
   - SMS/push starts as text import endpoint.
   - Android notification listener comes after Flutter shell is stable.

## Version 0.4 target

1. Shared data model
   - Real shared workspace database.
   - Invite code.
   - Personal/shared transactions.
   - Split rules persisted on transactions.

2. Home internet
   - Public endpoint if possible on current network.
   - Caddy reverse proxy.
   - HTTPS.
   - Windows autostart.
   - Firewall setup guide.

3. Daily use polish
   - Offline queue.
   - Biometric/PIN.
   - Backup/export.
   - Better bank import review.

## Acceptance for current work slice

- Markdown specification exists.
- Backend exposes server config, pulse, workspace and T-Bank notification endpoints.
- Backend tests cover new endpoints.
- Flutter project skeleton exists and targets Android/Web.
- Existing Android/Kotlin build remains intact until Flutter replaces it.
