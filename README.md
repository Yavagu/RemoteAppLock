# Remote App Lock — Android + PC MVP (v0.1)

A private, single-phone project for remotely locking launchable Android apps using a PC dashboard, Cloudflare Workers, and D1. The APK is preconfigured at build time. Phone setup grants Usage Access and Display over other apps, then configures notifications and background operation. No Accessibility service or notification-reading access is used. Your PC and phone may use unrelated networks. Only use it on a device you own or administer with informed consent.

## What is included

| Folder | What it does |
|---|---|
| `android/` | Preconfigured Kotlin Android app, installed-app inventory, Usage Access monitor, sliding application overlay, cloud polling, OTP verification |
| `cloudflare/` | Single-device Cloudflare Worker, D1 schema, encrypted admin-viewable OTP, rate-limited verification |
| `dashboard/index.html` | Browser PC control panel (plain HTML/CSS/JS, no build step needed) |
| `tests/` | Backend integration tests using Node.js 22+ built-in SQLite |

## Key behavior

- PC sees **launcher-visible installed apps** after the Android app uploads its inventory.
- PC locks an app for **30 min, 1 hr, 2 hrs, 3 hrs or custom duration** (1–10080 min).
- While the screen is awake and unlocked, Usage Access is checked about every 500 ms. When a locked app is detected, a full-screen application overlay displays a bottom-up animated focus screen and six-digit code form. Detection is delayed and depends on Android delivering usage events.
- The overlay consumes touches immediately, including during animation. It is removed on OTP success, lock expiry, foreground departure, screen lock, permission loss, or service shutdown. **Back to focus** opens Home; it does not unlock the target.
- The app does not force-stop or restart the target. Audio/background work can continue. Home, Recents, system UI and permission settings remain available.
- **Payment/system exclusions override remote locks.** Google Pay, PhonePe, Paytm, BHIM and the built-in payment list are excluded. The phone also discovers installed `upi://pay` handlers, launchers, dialers, Settings, installers and keyboards; bank identifiers/labels provide additional coverage. Apps combining payments with other features (for example Amazon and WhatsApp) are conservatively excluded as a whole.
- Recognizable embedded payment, checkout, UPI PIN and credential activity names are excluded even within another app. Unknown packages or missing activity information are not covered. These rules are in `OverlaySafetyPolicy.kt`; runtime handler discovery is in `SensitiveAppDiscovery.kt`. Package changes invalidate the cached policy, which is also refreshed periodically.
- Visiting Home, Recents, a payment app or Settings **does not clear a lock**. Returning to an eligible locked app shows the sliding OTP screen again after usage detection. Departures are processed even when leaving and returning happen within one polling interval.
- Only an authenticated PC admin can view/generate the code. The backend stores the code **encrypted with AES-GCM** using a Worker secret and permits at most five wrong attempts before needing a fresh code.
- **Successful OTP clears the lock permanently until the admin chooses Lock again.** If unused, the lock expires at the chosen time. OTPs themselves expire after five minutes and may be renewed from PC.
- Existing downloaded locks are still enforced when phone internet disappears; **new remote commands and OTP verification require internet**.
- The Android app embeds the Worker URL and a restricted `DEVICE_TOKEN` at build time; there is no pairing or phone-side setup form.

## First: deploy your backend (Cloudflare account needed)

Prerequisites: Node.js 20+ and a Cloudflare account. Cloudflare's free plan has usage quotas and may change. You do **not** need a VPS, Firebase account or common Wi-Fi network.

Run these commands in a terminal:

```bash
cd cloudflare
npm install
npx wrangler login
npx wrangler d1 create remote-app-lock-db
```

The last command prints a database UUID. Copy `cloudflare/wrangler.example.toml` to `cloudflare/wrangler.toml`, then replace `REPLACE_WITH_D1_DATABASE_ID` with that UUID. The real `wrangler.toml` is ignored so deployment-specific identifiers stay local. **Do not commit real admin tokens, device tokens, or OTP secrets.**

Then run:

```bash
npx wrangler d1 execute remote-app-lock-db --remote --file=./schema.sql
npx wrangler deploy
```

Generate **three different** random secrets. Run this command three times and save the outputs:

```bash
node -e "console.log(require('node:crypto').randomBytes(32).toString('base64'))"
```

Use the outputs as the dashboard admin token, AES-256 key, and fixed phone credential. Set all three Worker secrets:

```bash
npx wrangler secret put ADMIN_TOKEN
npx wrangler secret put OTP_SECRET
npx wrangler secret put DEVICE_TOKEN
```

`OTP_SECRET` must encode exactly 32 random bytes. Keep all three values private. The APK embeds only `DEVICE_TOKEN`, whose permissions are limited to phone sync and OTP verification; it cannot issue locks.

Wrangler prints your Worker URL, for example `https://remote-app-lock-mvp.<your-subdomain>.workers.dev`. Copy the actual URL (do not use this example as-is).

Test backend deployment in browser: `https://YOUR-WORKER-URL/api/health` should return an `ok: true` JSON response.

## Second: build the preconfigured APK

Install Android SDK 36 and JDK 17. Fetch the verified wrapper with `android\bootstrap-wrapper.ps1` on Windows or `android/bootstrap-wrapper.sh` on macOS/Linux. Create the ignored file `android/local.properties`:

```properties
sdk.dir=C\:\\Users\\YOUR_NAME\\AppData\\Local\\Android\\Sdk
REMOTE_APP_LOCK_URL=https://YOUR-WORKER.workers.dev
REMOTE_APP_LOCK_DEVICE_TOKEN=THE_SAME_VALUE_USED_FOR_DEVICE_TOKEN
```

From `android/`, run `gradlew.bat :app:assembleDebug` on Windows or `./gradlew :app:assembleDebug` elsewhere. The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.

## Third: install and use

1. On your PC, double-click `dashboard/index.html` (Chrome, Edge, or Firefox). No PC app installation and no extra web hosting are needed; it contacts your Worker over HTTPS. Some managed browser environments restrict `file://` fetches; if that happens, host `dashboard/index.html` as a static Cloudflare Pages site instead.
2. Enter the Worker URL and `ADMIN_TOKEN`, then click **Connect dashboard**. The token stays in the current browser tab/session, not source files.
3. Install and open the APK. It connects and uploads its launchable apps automatically—there is no pairing screen.
4. Follow setup to enable **Usage Access**, **Display over other apps**, and notifications. Notification permission is for CarrotLover's own status notification; do not grant notification-reading access. On an upgrade, the old Accessibility service is removed; leave CarrotLover Protection off if your phone still lists it.
5. Tap **Allow background operation** and approve unrestricted battery use. On Xiaomi/Redmi, use the setup shortcuts to enable **Autostart**, **Open new windows while running in the background** / **Display pop-up windows while running in the background**, and battery **No restrictions**. Names vary by OS; vendor settings cannot be automatically verified. Reopen CarrotLover after granting permissions. A boot/update receiver attempts to restart monitoring when permissions are already granted, but OEM restrictions can require reopening it manually.
6. Refresh the PC dashboard, choose an app, and press **Lock**. The command normally reaches the phone within 15 seconds.
7. Use the displayed six-digit OTP on the phone to unlock. If it expires after five minutes, generate a new OTP on the PC.

## How the server works

- Authentication: `ADMIN_TOKEN` protects `/api/admin/*`; the fixed, restricted `DEVICE_TOKEN` protects `/api/device/*`.
- The first valid device request automatically creates the single `My phone` database record.
- `POST /api/device/apps` uploads a launchable-app inventory.
- `GET /api/admin/devices` + `GET /api/admin/devices/:id/apps` populate the dashboard. Admin views can see current OTPs until they expire.
- `POST /api/admin/devices/:id/locks` starts/restarts a lock with `minutes` + `packageName`.
- `POST /api/admin/devices/:id/otp` rotates an OTP; `POST /api/admin/devices/:id/unlock` unlocks an app manually.
- `GET /api/device/state` returns **only lock status**, not OTPs; `POST /api/device/verify` verifies an OTP and changes lock state to unlocked.

## Test backend locally (no Cloudflare credentials needed)

Node.js **22+** is required for the built-in SQLite integration test adapter.

```bash
cd cloudflare
node --test ../tests/*.test.mjs
```

The tests cover automatic single-device registration, app upload, lock, wrong OTP, OTP rotation, successful OTP, permanent unlock, re-lock, manual unlock and attempt limits.

## Important limitations — read before relying on this

- **This is a development prototype, not a secure parental-control or enterprise MDM product.** Usage Access or overlays can be disabled, the app can be uninstalled/force-stopped, and OEMs can kill services. Polling leaves a short exposure window. You cannot guarantee that an ordinary Android app will block every other app in all situations.
- The phone's installed app list is limited to **launchable applications** because Android restricts broader package visibility. The current implementation doesn't monitor non-launchable system components, work profiles, or other users.
- Usage event delivery and overlays differ by manufacturer/Android version. Apps can hide third-party overlays; system windows appear above them. Split-screen, picture-in-picture and delayed/missing usage events are not reliable enforcement paths. Startup recovery inspects the last 24 hours of events; very old foreground sessions may require reopening the target.
- Removing Accessibility addresses that permission dependency, **not a guarantee of Google Pay compatibility**. Financial apps may reject overlay capabilities even when no overlay is visible. Test on your phone with protection enabled and no active lock screen. Do not keep invisible overlays attached.
- Exclusions cannot guarantee literally zero overlap during transitions: Usage Access events arrive after a screen change. Unrecognized banking apps, obfuscated activities, payments inside an unchanged WebView/activity, and system surfaces without usage events cannot all be identified by this API. Known exclusions prevent intentional attachment; stale overlays are removed on observed departure. Add missed app identifiers to the policy and validate on the physical phone. No screen contents are inspected.
- Remote state uses **15-second polling**, not push. It is not instantaneous; battery/network usage is higher than a push design. Some Android versions may pause background work.
- OTP verification needs internet. Local cache supports previously downloaded locks, but isn't tamper-proof; phone clock changes can affect locally enforced lock expiration.
- The backend stores installed-app names/package IDs and last-seen timestamps. The restricted device credential is embedded in the APK and can be extracted by a determined user, but it has no admin privileges. Rotate it and rebuild the APK if exposed.
- Dashboard currently has **one admin token** for all devices. It does not support multiple admin accounts or a recovery/forgot-password workflow.
- Opening the dashboard as a local HTML file does not host it online; the PC browser makes HTTPS requests to the deployed Worker. Cloudflare Workers+D1 can operate within the provider's free tier for modest testing, subject to current quota limits.
- **An app unlocks as soon as the correct OTP is accepted.** The chosen duration limits how long an *unused* lock may last, not how long an unlocked app remains open. This matches the intended workflow.
- Build and JVM tests cannot establish on-device overlay behavior or Google Pay compatibility. Follow [the Android validation checklist](tests/android-manual.md) on an Android 8.0+ device, including your target Xiaomi/Redmi version, before relying on the new flow.

## Troubleshooting

| Problem | Check |
|---|---|
| Gradle sync fails | Run `bootstrap-wrapper` first; use JDK 17, install Android SDK Platform 36, allow Gradle network access |
| PC says invalid admin token | `ADMIN_TOKEN` Worker secret must match exactly; check Worker deployment and copied URL |
| Phone says build is not configured | Rebuild with `REMOTE_APP_LOCK_URL` and `REMOTE_APP_LOCK_DEVICE_TOKEN` in `android/local.properties` |
| PC list is empty | Open the phone app once with internet, verify `DEVICE_TOKEN` matches, then refresh the dashboard |
| Lock doesn't appear | Grant Usage Access and Display over other apps, reopen CarrotLover, check Xiaomi background pop-up settings, verify the app shows Locked on PC and allow time for cloud sync; reopen the target |
| OTP fails | Check six digits, 5-minute code validity, connected phone internet and 5-attempt limit; generate a new code on PC |
| App stops enforcing in background | Allow notifications, approve **Allow background operation**, set battery use to **Unrestricted**, and enable vendor-specific Auto start if present |
| Google Pay still warns | Confirm old Accessibility is off and no lock overlay is showing. Overlay permission may itself affect risk checks; compatibility must be tested on the phone |

## Future milestones

Device-owner-based stronger blocking for managed phones, FCM push notifications, Android Keystore for token security, admin accounts, revoke device, lock audit log, more robust lifecycle handling and a signed APK release.
