# همسایه‌یار — Neighbour SOS

Android BLE emergency-request prototype. A neighbour enters their name and unit, sends SOS, and participating phones receive and automatically relay the original request. No account, server or Internet connection is required.

## Interface and viewing on Linux

The app has a Persian-only, right-to-left interface, independent of the device language. The motto is «هیچکس تنها نیست». Name and unit are saved once in the first-run setup dialog and can be changed under «تنظیمات ← نام و شماره واحد». Existing saved profiles are reused. The home screen contains a large «درخواست کمک» button, connection status and received requests. Sound controls are inside «تنظیمات صدا» to keep the main screen simple. Connection and error messages are Persian too.

See [View and run on Linux](docs/LINUX.md) for Android Studio's layout preview, an interactive emulator, and mirroring a real phone with scrcpy. The app has been run on an Android 10 software emulator in this Linux cloud environment. Actual screenshots and the checked UI flows are documented in docs/LINUX.md. Bluetooth delivery and offline Persian audio have not been validated on physical phones.

## Use

- Android 8.0+ (API 26). Activate background reception on each participating phone and keep Bluetooth enabled. Android 10–11 require foreground and background Location permission (Always) for BLE discovery; Android 11 and earlier also require the system Location setting. Android 12+ use nearby-device permissions instead.
- Enter a short name and apartment/unit once, then tap «ذخیره مشخصات». The profile is stored locally immediately and reused across app launches and SOS requests; it can be edited in settings.
- Tap **درخواست کمک** and confirm sharing the name and unit. Other phones show these original details under **درخواست‌های همسایه‌ها**, including whether reception was direct or relayed.
- A request can involve at most **30 app devices total, including the origin** (29 additional recipients across all relay branches, not 30 per hop). Receiving phones may forward only within their assigned remaining budget, preserving the original ID and expiry. Relaying also stops after three forwards (up to four Bluetooth links) or two minutes. This is local Bluetooth delivery, not an Internet broadcast.
- Up to eight requests can be queued on one phone. Phones advertise presence while the app is active; they are not permanently connected to every neighbour. Directed GATT connections are brief and serialized, with a five-second outgoing timeout and an eight-second incoming limit. Discovery, connection establishment and failures add delay; reaching all 30 devices is not guaranteed.
- **توقف درخواست من** stops this phone's original transmission. It does **not** recall copies already relayed; those expire on their own. Minimizing the app or locking the screen leaves the started foreground service running. Stopping background reception from its notification/settings stops Bluetooth and speech on that phone.

## Background reception

Tap «فعال‌کردن دریافت پس‌زمینه» and grant the requested permissions. A Persian foreground-service notification stays active while background reception is enabled. The non-exported `SosService` owns Bluetooth discovery, GATT transfers, original-request deadlines, durable duplicate claims and Persian speech; activities only bind to it for UI state and controls. Pressing Home, locking the screen or destroying the activity does not stop the started service. Already received alerts are stored locally until their original expiry and restored for display without re-forwarding or repeating speech.

New distinct SOS requests create ordinary high-importance Android notifications, with vibration/notification sound subject to system settings, and can be spoken with an installed offline Persian voice. Request notifications expire with the request and hide their private details on the lock screen according to Android settings. These are app notifications, not government Emergency Alerts or a bypass of Do Not Disturb. Android 13+ ask for notification permission; if denied, receiving/relaying may continue but request notifications will not appear. The app warns when notifications are blocked.

On Android 10–11, the app separately requests/guides the user to background Location (Always), which BLE discovery requires on those versions. It does not collect geographical coordinates. On newer versions it requests Bluetooth scan/advertise/connect instead. The connected-device foreground-service type and its Android 14+ permission are declared. A bounded partial wake lock is held only while live SOS work/announcements are pending (at most about two minutes per request); idle reception does not hold a permanent wake lock.

The persistent notification and settings both offer «توقف دریافت پس‌زمینه». This stops radio work and speech, removes the ongoing notification, and remembers the choice; reopening alone does not undo that explicit stop. Tap activation to resume. Turning Bluetooth off pauses radio work; turning it back on lets the active service retry. An Android system restart of the service does not recreate old relay budgets; pending transfers may be lost, keeping the count safe rather than guaranteeing delivery.

**Force Stop**, Android's running-apps Stop control, reboot, permission revocation, battery restrictions and manufacturer task killers can stop reception. Force Stop cannot be bypassed: open the app again to restart an enabled service. There is no boot receiver; reopen after reboot. Swiping the recent-task card is intended to leave the service running (`stopWithTask=false`), but manufacturer behavior varies. No app can guarantee uninterrupted emergency reception under all device conditions.

### Background validation

The current API 29 Linux emulator verified: a real foreground notification; service survival after Home and screen sleep; no restart after Force Stop; restart after explicit reopen; the notification's Stop action; retaining an explicit stop across reopen; and resuming only after explicit activation. See [recorded lifecycle results](docs/validation/background-service-api29.txt) and [the foreground notification](docs/screenshots/background-notification.png). This image has no working BLE radio or Persian voice engine, so these checks prove component lifecycle, not locked-screen radio delivery or audio. Android 12–15 permission/FGS behavior and OEM battery/task-killer behavior still need physical-device checks.

Physical background acceptance: with two or more phones, start reception, press Home/lock/swipe away the UI, send an actual SOS from another phone, and confirm timely notification, speech (when enabled), and forwarding within the original 30-device budget and expiry. Repeat with notification denial, Bluetooth toggling, battery saver/Doze, permission revocation, explicit Stop, reboot and Force Stop. Confirm the explicit stop survives reopening and never restarts by itself.

## Offline Persian announcements

New received SOS requests automatically speak the original name and unit in Persian, for example: «درخواست کمک اضطراری! امینه، واحد ۲۰۴، به کمک نیاز دارد.» Returning relays are deduplicated before speech. Enter names in Persian for the best pronunciation; the app does not translate or transliterate names. Unit digits are normalized to Persian digits.

The app selects only an installed Persian (`fa`) voice that the Android TTS engine marks as not requiring a network connection. It prefers `fa-IR` and refuses to use a network-only voice. No voice model is bundled. Availability depends on the phone's selected TTS engine and installed voice packs; the app cannot guarantee a vendor's voice availability or offline behavior without device testing. The app itself has no Internet permission.

Use **نصب و تنظیم صدای فارسی** to select an engine and download its offline Persian voice (the initial download may need Internet). Return to the app and tap **آزمایش صدا**. If you install a new engine after initialization failed, reopen the app. If no suitable local voice exists, Persian guidance explains the missing prerequisite, while text alerts and Bluetooth relaying continue.

The **خواندن نام و واحد با صدا** switch persists across app launches. Speech uses the phone's media volume; it does not force maximum volume, override Do Not Disturb, or guarantee audibility. Disabling speech or stopping background reception stops playback and clears queued announcements. With background reception active, new requests can be spoken while the screen is locked or the UI is closed. Names and units may be audible to bystanders.

### Speech device checks (not yet executed)

1. With an offline Persian voice installed, enable airplane mode, turn Bluetooth back on, and tap **آزمایش صدا**. Confirm intelligible Persian output without Wi-Fi/mobile data.
2. Receive an SOS with a Persian name and unit; confirm it speaks the original identity once, including when received through a relay. Repeated copies must not repeat the speech.
3. Test phones with no TTS engine, no Persian voice, a network-only Persian voice, and an installed offline voice. Only the last should play speech; all must continue showing text alerts.
4. Disable speech and receive another SOS; confirm silence and text reception. Reopen the app and verify the switch remains off.
5. Test multiple distinct alerts, media volume at zero, and leaving the app during speech. With background reception enabled, announcements should continue without the activity. Explicitly stopping reception or disabling speech must stop playback; muted audio must not be mistaken for failed Bluetooth reception.

## Transport and limits

BLE presence advertisements contain only a stable eight-byte app-node identifier, not the name, unit or SOS. Discovery samples signal strength for five seconds before planning; RSSI is smoothed, stale samples are dropped, and stronger signals are preferred. Phones cannot calculate the globally nearest 30 people across hidden relay hops, an exact radius, or apartment/building membership from Bluetooth signal strength. Relaying can extend beyond the origin's direct radio range, but only over participating local Bluetooth links and within the shared count, expiry and hop limits.

SOS details are delivered by directed GATT writes, not public characteristic reads. Before writing, the sender reads the destination's node identifier to verify it matches the selected recipient despite BLE address rotation. The protocol negotiates enough MTU for a complete request; peers that cannot complete negotiation or delivery within the timeout may be missed. No Bluetooth pairing or permanent all-to-all connection is needed.

### Shared 30-device budget

The origin starts with 29 additional-device slots. A phone freezes its strongest known recipient IDs and assigns each branch a disjoint share of its remaining slots. Every branch pays one slot for its receiver before that receiver can forward. For example, if only one neighbour is reachable, that neighbour consumes one slot and receives a budget of 28 for onward propagation. If 29 neighbours are selected directly, each gets zero onward budget and cannot relay.

A plan is fixed for that request. Retries go only to the same selected IDs with the same grants (at most two attempts per peer). Failed deliveries waste their slots; they cannot be replaced or reallocated, since an apparently failed write might already have arrived. This deliberately prefers the total cap over maximum reach. When no neighbour has been found, planning waits for discovery until expiry without spending any budget.

Request claims are committed to local storage **before** forwarding and retained past expiry, including across activity/process restarts, so a restarted app cannot spend a previously received grant again. Failed persistence prevents forwarding. Explicitly stopping reception, disabling Bluetooth or losing the service process discards pending transfers rather than reissuing a fresh budget. Activity pause, screen lock and closing the UI no longer stop the service. A stable persisted node ID prevents a rotating Bluetooth address from being counted as a new selected recipient. The cap is an invariant of compliant, unmodified app instances and their stored identities; these self-reported identifiers and messages are unauthenticated, so it is not a security guarantee against malicious software, cloned state or protocol forgery.

The packet format and service ID changed for budgeted directed delivery. **Update every participating phone**; older unrestricted-pull versions cannot participate in this protocol. Accurate phone clocks remain necessary for expiry. Neither the budget nor RSSI guarantees geographical closeness, delivery, verified identity, an emergency-service call, or that someone will help. Names/units are self-reported and unencrypted; nearby attackers can spoof or intercept BLE traffic. Production use requires authentication, abuse prevention and physical-device reliability testing.

## Build

Use Android Studio, SDK Platform 35, Build Tools 35.0.0, and JDK 17 or 21. Versions: Gradle 8.11.1, Android Gradle Plugin 8.9.2, Kotlin 2.1.20.

```sh
./gradlew --no-daemon assembleDebug lintDebug testDebugUnitTest
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

Unit tests cover identity/budget serialization, duplicate suppression (including recreated inboxes and failed durable claims), signal ranking and stale observations, fixed recipient sets, exhausted retries, branching-budget conservation, simulated multi-hop networks, expiry, hop limits and malformed input. They do not validate the Bluetooth transport.

## Physical-device acceptance checks (not yet executed)

1. Install this version on three BLE Android phones A, B and C. Grant permissions, activate background reception and enable Bluetooth. Start with screens open for the baseline radio test, then repeat while locked/minimized as described above.
2. Enter a name and unit on A. Confirm blank or overly long profile fields prevent sending. Send SOS and verify B shows A's exact details.
3. Place C beyond A's measured direct range but within B's range. Verify C receives A's details through B, with a relayed label. Then repeat with B disabled to establish that C is not receiving A directly.
4. Keep all phones together and verify repeated packets and returning relays do not duplicate the same request. Send a distinct request on another phone and verify both requests are shown.
5. Verify requests disappear and SOS delivery stops after their original expiry; presence advertisements may continue while the app is open; forwarding must not restart the two-minute window. Verify hop-three receivers do not forward again (requires additional devices or transport instrumentation).
6. Stop A early: A stops its own SOS transmission; existing B/C copies remain only until the original expiry. Leave B's screen or lock it and verify receiving/relaying continues. Use the persistent notification stop action and verify relaying stops.
7. Test denied/revoked permissions, Bluetooth disabled, unsupported advertising, disconnects during reads, and expiry during a read without crashes. Restart listening after failures.
8. Reverse roles and test across Android 11 and Android 12+. Measure latency and range before claiming practical coverage.

Profile acceptance: on a fresh install verify that blank setup cannot save; save a name/unit and restart, confirm setup no longer appears, then edit/cancel/save from settings and confirm only saved changes persist. The current first-run setup, home screen and settings have been captured on the Android 10 emulator. Saving and persistence across relaunch still need the acceptance check above. Current screenshots are linked in docs/LINUX.md.

Thirty-device radio acceptance (not yet executed): test with more than 30 participating devices, a sparse multi-hop topology, lost acknowledgements, address rotation and restarts mid-request. Record the distinct recipients for a single request UUID across all devices and verify the origin plus all recipients never exceeds 30. Check that an exhausted/zero-budget node cannot forward, and no unused slot from a possibly delivered write is reassigned. Simulations and unit tests do not establish physical-radio behavior.
