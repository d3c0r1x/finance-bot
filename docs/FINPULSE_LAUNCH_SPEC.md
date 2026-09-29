# FinPulse launch plan

## Goal

Turn the current Android/FastAPI prototype into a phone-installable local-network build.

The app name is **FinPulse** (`ФинПульс` in Russian). The name keeps the existing product idea: money has a rhythm, and the app shows spending pulse, receipts, budgets and debts in one place.

## Current machine network

- LAN interface: `Ethernet`
- LAN address: `192.168.3.48`
- Backend port: `8000`
- Phone API URL: `http://192.168.3.48:8000`
- Emulator API URL remains available manually: `http://10.0.2.2:8000`

The backend must bind `0.0.0.0:8000` for phone access. The Android debug build allows cleartext HTTP through its debug manifest.

## Scope

1. Server
   - Add a Windows-friendly mobile server launcher.
   - Default host: `0.0.0.0`.
   - Default port: `8000`.
   - Keep data under `data/mobile`.
   - Generate or reuse `FINANCE_JWT_SECRET`.
   - Print phone URL after startup.
   - Add an optional firewall helper script for TCP `8000`.

2. Android app
   - Rename app label to `FinPulse` / `ФинПульс`.
   - Set default backend URL to `http://192.168.3.48:8000`.
   - Keep editable backend field on login and profile.
   - Improve login screen visual hierarchy: brand mark, server status hint, cleaner card layout.
   - Add generated launcher icon.

3. Documentation
   - Document phone setup path.
   - Document emulator override.
   - Keep existing Telegram flow untouched.

## Acceptance

- Backend starts from a single script and listens on `0.0.0.0:8000`.
- `GET http://127.0.0.1:8000/api/v1/health` returns `{"status":"ok"}`.
- Android build succeeds.
- APK contains new app name and icon.
- App opens on emulator with prettier login screen and default LAN backend.
- Existing backend tests pass.

## Non-goals

- Public cloud hosting.
- HTTPS certificate setup for LAN.
- App store release signing.
- Router port forwarding.
- Changing legacy Telegram runtime.
