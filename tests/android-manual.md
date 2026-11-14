# Usage Access / focus overlay device validation

Run on Android 8.0+ and the target Xiaomi/Redmi OS. No connected device or configured AVD was available during implementation, so these checks remain pending. Capture setup and overlay screenshots on the test device; do not include OTPs, tokens, or personal notifications.

## Build checks

From `android/`, with JDK 17 and SDK 36:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

From `cloudflare/`, run `npm test`. The JVM tests exercise foreground switching, overlapping event queries, same-timestamp events, activity transitions and clock-reset recovery.

The payment-policy tests also cover built-in payment apps, discovered UPI handlers, bank labels, OEM system handlers, embedded payment/credential activities, unknown foregrounds, and return from payments to a locked app. Tracker tests cover fast Recents round trips within one query batch and stop events without a preceding pause.

## Upgrade and permissions

1. Install the new APK over the old build using the same signature. Confirm no CarrotLover Accessibility service remains enabled.
2. Grant Usage Access and Display over other apps using setup. Allow its own notifications and unrestricted battery use. No notification listener permission should be requested.
3. On Redmi, enable Autostart and background pop-up windows in the supplied settings pages. Confirm monitoring resumes after returning to the app.
4. Revoke each of Usage Access and overlay permission separately. The overlay and foreground service must stop. Reopen the app: setup must show the missing permission, then restart monitoring after it is restored.

## Lock and authentication

1. Lock a harmless test app in the dashboard. Allow cloud sync, then open it. Record detection delay and whether the bottom-up animation completes without flicker. The app's underlying controls must not receive taps while the overlay is attached, including during animation.
2. Use portrait and landscape, a small display, larger font size, and an open numeric keyboard. The input, feedback and both buttons must remain reachable by scrolling.
3. Enter a wrong OTP, an expired OTP, then the valid OTP. Failed verification must retain the lock and show feedback. Success removes the entire overlay and leaves the target available until a new admin lock.
4. While verification is in flight, leave the app or let a lock expire. Completion must not reattach an old screen. Apply a new admin lock while an earlier verification completes: a newer downloaded lock version must survive.
5. Use Back to focus, hardware Back, Home gesture, Recents, and switch to an unrelated app. No overlay should stick over the unrelated app. Return to the locked target: it should block again. Home must not clear the lock.
6. With the overlay open, manually unlock from the dashboard or let the lock expire. The overlay must disappear without an OTP.
7. Apply a lock remotely while already using the target: it must appear after the next state sync without reopening the target.
8. Repeat Recents -> target at least ten times, including very quick round trips. The sliding lock should return on every observed re-entry, with no cleared lock or accumulated delay. Repeat while offline with a downloaded lock. Record measured response time; polling is not a zero-gap security boundary.

## Payment and system exclusions

1. Deliberately select Google Pay, PhonePe, Paytm and BHIM in the dashboard. Their locks must not produce an overlay. Do the same for an installed bank app and a UPI handler outside the built-in list.
2. From a locked test app, switch directly to each payment app. The old window must be removed when the transition is detected. Return to the test app: its lock must still apply and animate again.
3. Open an embedded UPI/payment activity in an otherwise lockable test app. Verify recognized payment activity names suppress the overlay, then returning to the ordinary activity restores the lock. Payments in an unchanged/obfuscated WebView cannot be reliably identified; record this limitation.
4. Try Home, Recents, Settings, permission prompts, package installation, dialer, credential prompts, keyboard settings and keyguard. No lock may be intentionally attached to their recognized foreground packages/activities, even if remotely locked. Entering OTP with the numeric keyboard must remain possible.
5. Install/update a payment app while monitoring runs. Verify classifications refresh without restarting protection. Unknown launchable apps should remain uncovered until discovery succeeds.
6. Measure any transition overlap separately from attachment policy: delayed usage events can leave a brief old overlay until departure is reported. Do not describe these tests as proving a universal zero-overlap guarantee.

## Lifecycle and limits

1. Turn the screen off and lock the device during OTP entry. The overlay must be removed and must not cover the keyguard. Unlock back into the target and verify enforcement resumes.
2. Swipe CarrotLover from Recents, reboot, and test process recovery. Keep OEM Autostart enabled. Explicit force-stop is expected to require reopening CarrotLover.
3. Disconnect the internet after downloading a lock: keep enforcing it until expiry, show a useful OTP connection error, and recover on reconnection. Cached locks must survive a service restart.
4. Test split-screen, picture-in-picture, notification shade, and apps that suppress overlays. Record limitations rather than treating this as device-owner enforcement. The target's background audio/process is not stopped.
5. Leave the phone idle, then repeat. Measure battery impact and actual detection delay; 500 ms is a polling interval, not a guaranteed response time.

## Google Pay compatibility (physical phone only)

With Accessibility off, Usage Access and overlay permission on, and no lock screen attached, check whether the prior warning returns. Repeat after leaving a locked test app and after OTP unlock. Never infer compatibility solely from the build or from another app's behavior. If Google Pay rejects overlay capability, record it: this implementation cannot guarantee acceptance.
