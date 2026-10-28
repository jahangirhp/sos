# View and run on Linux

## View the interface without a phone

1. Install [Android Studio for Linux](https://developer.android.com/studio).
2. Open the `sos` project folder and allow Gradle sync. Install SDK Platform 35 and Build Tools 35.0.0 when prompted. Android Studio's bundled JDK can be used.
3. Open `app/src/main/res/layout/activity_main.xml` and choose **Design** or **Split** in the layout editor. This previews the actual native home-screen layout, including Persian text and right-to-left direction, without booting Android. It does not run Bluetooth or speech.

## Run interactively in an emulator

1. In Android Studio, open **Tools → Device Manager → Create Device**.
2. Choose a phone and an Android system image with API 26 or newer (API 35 is recommended).
3. Start the virtual device, select it in the toolbar and press **Run**. Alternatively, drag `app/build/outputs/apk/debug/app-debug.apk` onto a running emulator and open «همسایه‌یار».

Linux emulator acceleration needs CPU virtualization and a working `/dev/kvm`. Enable virtualization and configure KVM according to [Android's Linux acceleration guide](https://developer.android.com/studio/run/emulator-acceleration#vm-linux). This cloud environment has no `/dev/kvm`, but an official Android 10 (API 29) image was booted with software CPU and graphics emulation. The actual app was installed and its home screen, blank-profile validation, sound dialog and voice-test control were exercised. First boot took about three minutes and Android System UI initially timed out; choosing Wait allowed it to recover. Hardware acceleration is recommended for comfortable desktop use.

Use the emulator to inspect layout, profile validation, confirmation and sound settings. Real Bluetooth relay coverage cannot be validated this way. Missing Bluetooth on an emulator should produce a Persian explanation. Offline Persian speech still requires an installed, supported local voice; an emulator image may not include one.

## Use a real Android phone from your Linux desktop

This is the recommended option for checking both the screen and Bluetooth behavior.

1. Install Android platform tools (`adb`) and [scrcpy](https://github.com/Genymobile/scrcpy/blob/master/doc/linux.md) using their Linux installation instructions.
2. On your Android phone, enable Developer options and USB debugging. Connect via USB and approve the phone's debugging prompt.
3. From the project directory, run:

```sh
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.sos.nearby/.MainActivity
scrcpy
```

If several devices are listed, select one with `adb -s SERIAL ...` and `scrcpy -s SERIAL`. Activate background reception before minimizing or locking the phone. Use at least three physical Android phones for the relay acceptance checks in the main README.

The app's own interface is Persian on any system language. Android permission dialogs, the keyboard and device speech settings may follow the phone's system language.

## Captured Linux emulator preview

The home/profile/settings screenshots below were captured before background reception was added. The foreground-notification screenshot is from the current foreground-service build:

- [Background reception notification](screenshots/background-notification.png)
- [Home screen (before background reception)](screenshots/home.png)
- [One-time profile setup](screenshots/profile-setup.png)
- [Settings menu](screenshots/settings-menu.png)
- [Persian sound settings](screenshots/sound-settings.png)

The virtual display is 480 × 960 at 200 dpi. The image has no usable speech engine installed, so the screenshots validate the missing-engine guidance rather than audio playback. Bluetooth delivery and offline Persian synthesis still need physical-device testing.

On this prepared cloud machine, emulator files and helpers are retained outside the checkout:

```sh
/workspace/start-sos-emulator.sh
```

Run that command in a retained terminal session. It starts a headless emulator named `sos-preview` on ADB serial `emulator-5554`, using software acceleration when KVM is absent. Wait until `/workspace/android-sdk/platform-tools/adb -s emulator-5554 shell getprop sys.boot_completed` returns `1`. From another shell, `python /workspace/capture-sos-preview.py` installs the current APK, checks the controls and refreshes these screenshots. No live browser preview is provided. Stop only this preview device with `/workspace/android-sdk/platform-tools/adb -s emulator-5554 emu kill` when finished.
