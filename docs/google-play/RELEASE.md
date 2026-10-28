# Google Play submission status

Not uploaded or published. No Play Console connection, authorized publishing credentials or release upload key is configured in this environment.

## Prepared

- Persian listing: listing-fa.md (draft, review before submission).
- Bundle task: `./gradlew bundleRelease`. Without release signing configuration its output is unsigned and cannot be uploaded.
- Current application ID: `com.sos.nearby`; version 0.1.0 / code 1; target SDK 35.
- Existing screenshots are under ../screenshots; some predate background receiving. Capture current device screens for the final listing.

## Required before submission

1. Verified Google Play developer account and authorized Play Console/API access. Create the app in Play Console if it does not exist. Never paste passwords or signing keys into chat; use secure credential provisioning.
2. Confirm ownership/availability of the permanent application ID before first upload. Supply an upload keystore through secure storage and enable Play App Signing; keep a recoverable backup. Do not use the debug key or generate a throwaway release identity.
3. Confirm the target API requirement currently shown by Play Console; the project currently targets 35. Requirements may have advanced since project creation.
4. Developer identity, support email, intended countries, audience, pricing and public privacy-policy URL. The policy must identify the responsible developer, explain local name/unit storage and Bluetooth sharing, retention, deletion and contact. Do not claim that no information is shared: SOS identity goes to other participating devices. Assess the exact Data safety answers against current Play definitions; developer has no server, analytics or Internet permission in this app.
5. 1024×500 feature graphic, and current phone screenshots meeting Console requirements. A custom house-and-SOS adaptive launcher icon and 512×512 store icon (icon-512.png) are included.
6. Complete content rating, ads declaration, app access, target audience, Data safety and foreground-service declarations. Background location is declared through Android 11 for BLE discovery: assess Play's background-location policy and provide prominent disclosure/consent, justification and demonstration where required. Do not claim approval is guaranteed.
7. Test actual BLE delivery/relay, background/screen-locked behavior and offline Persian speech on physical Android phones, including modern Android versions. Existing emulator checks validate lifecycle only. Do not advertise guaranteed emergency delivery.
8. Upload a signed AAB to an internal test track first. Complete any account-specific closed-testing/production-access requirements shown by Console, review pre-launch reports, then submit a production release for Google review. Uploading does not itself mean public availability.

## Signing and release checks

Configure release signing using secure environment/file credentials after the permanent upload key is available. Never commit credentials or include passwords in shell command arguments or logs. Verify the signed bundle certificate and expected upload-key fingerprint before uploading. Run release lint, unit tests and device acceptance against the release artifact. Increase versionCode for subsequent uploads.
