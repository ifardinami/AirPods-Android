# AirPods — Android companion (AirPods Pro 3)

A small, native Java Android companion aimed at Android 11 phones such as the Realme X2 Pro. The first build focuses on the functions that can be implemented without root or system Bluetooth modifications:

- Read the public Apple BLE proximity advertisement for AirPods Pro 3 (model `0x2720`).
- Show left, right and charging-case charge levels when the earbuds advertise them.
- Show charging flags, RSSI and the last advertisement time.
- Offer standard Android media-key controls (previous, play/pause, next).
- Keep an optional foreground BLE monitor running after the app is closed.
- Restore monitoring after reboot if the user enabled it and the required permissions remain granted.

The app has no `INTERNET` permission, analytics, account, ads or cloud service. Battery snapshots and preferences are stored locally in Android SharedPreferences.

## Important limitations on stock Android 11

1. **ANC / Transparency / Adaptive mode switching is not implemented.** AirPods use Apple's proprietary Accessory Protocol over classic Bluetooth BR/EDR L2CAP (PSM `0x1001`) for these settings. Android's public `createL2capChannel()` API is for LE Connection-oriented Channels, not this classic channel; LibrePods documents that other Android versions generally need root plus Xposed to work around the Android Bluetooth-stack limitation. On this unrooted Android 11 phone, the app deliberately does not show fake buttons that claim to change listening modes. Configure the AirPods stem press on an Apple device and use the stem to cycle modes.
2. **Battery health is not available.** The public advertisement reports current charge levels, not design capacity, cycle count or battery wear. This app therefore shows charge level only and does not invent a battery-health percentage.
3. **Battery values are estimates.** The public advertisement exposes coarse battery values in 10-point steps, not exact percentages. A component may report `not available` (`0xF`) until the case is opened or the earbuds wake and update their advertisement. The app prefixes readings with `≈` to make this limitation clear.
4. **Pairing remains an Android system operation.** Pair the AirPods once in Android Bluetooth settings. The app detects/monitors them afterwards; Android does not allow an ordinary app to silently complete secure Bluetooth pairing or force itself to open on every connection. A persistent notification is used instead.
5. **Media controls depend on the active media session.** Android routes the standard media keys to the current media app. Some players may handle them differently. Automatic ear-detection pause/resume is not enabled in this first build because the public BLE status bits need validation against this exact firmware.
6. **No physical-device test has been performed in this build environment.** The BLE decoder has an offline self-test, but actual Realme X2 Pro / AirPods Pro 3 scanning, charging-state interpretation and media-session behaviour must be verified on the phone.

## Permissions and setup on the Realme X2 Pro

1. Pair the AirPods in **Settings → Bluetooth** first.
2. Install and open AirPods.
3. Turn on Bluetooth and Android's Location quick setting. Android 11 requires Location permission for BLE discovery; the app does not read GPS coordinates.
4. Tap **Scan now** with the case open near the phone or an earbud outside the case.
5. For monitoring while the app is closed, enable **Background monitoring** and set the app's Location permission to **Allow all the time** in App info → Permissions → Location.
6. If Realme's battery manager stops the service, allow background activity / remove battery restrictions for AirPods in the phone's battery settings.

The case may show `--` while asleep or not advertising. Open the case near the phone and wait for a new advertisement.

## Build in Android Studio

- Android Studio with JDK 17
- Android SDK Platform 35
- Android SDK Build-Tools 35.0.0
- Gradle 8.9 (Android Gradle Plugin 8.7.3)

Open this folder as a Gradle project and run **`:app:assembleDebug`**. The debug APK is created at:

`app/build/outputs/apk/debug/app-debug.apk`

The included GitHub Actions workflow (`.github/workflows/build-apk.yml`) can build the debug APK and publish it as a workflow artifact when this source folder is pushed to a GitHub repository and the workflow is manually run.

## Offline parser test

Run:

```sh
./tools/test-parser.sh
```

Expected output:

```text
AirPods BLE parser self-test: PASS
```

The self-test covers a Pro 3-format packet, primary-bud side mapping, case-battery nibble decoding, charging flags, out-of-range battery values, invalid model rejection, truncated packets and extraction of Apple manufacturer data from a raw BLE advertisement. These are parser tests against documented sample layouts, not a substitute for a live capture from your own earbuds.

## Protocol notes

The parser uses the published AirPods Pro 3 model identifier `0x2720`, Apple company ID `0x004C`, message type `0x07`, primary-first bud battery nibbles in byte 6, and case battery in the low nibble of byte 7. The charging flags are read from the high nibble of byte 7. Public BLE battery values are coarse; the trailing encrypted portion is ignored. Left/right orientation and case-field interpretation must still be confirmed against a live advertisement from the user's AirPods Pro 3 firmware before this build can be described as hardware-validated.

References used for protocol research:

- https://github.com/AlexAli29/auricle/blob/main/docs/PROTOCOL.md
- https://github.com/t4t5/podpower
- https://github.com/fischejo/airpods-notify/blob/master/doc/proximity_protocol.md
- https://github.com/d4rken-org/capod
- https://github.com/librepods-org/librepods

The app is an independent project and is not affiliated with or endorsed by Apple Inc.
