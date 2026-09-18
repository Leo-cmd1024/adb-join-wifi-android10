# adb-join-wifi — Android 10+ fork

Forked from upstream [`steinwurf/adb-join-wifi`](https://github.com/steinwurf/adb-join-wifi) **v1.0.1**
to make the app usable on **Android 10 (API 29) and above**, where the original
`WifiConfiguration`/`addNetwork` path crashes with
`SecurityException: no location permission` (the "open-and-close" symptom) and can
no longer add networks for a non-device-owner app.

## What changed vs upstream 1.0.1

1. **Android 10+ connect path (the real fix).**
   `MainActivity` now branches on `Build.VERSION.SDK_INT`:
   - **API ≥ 29** → `WifiNetworkSuggestion` (`WifiManager.addNetworkSuggestions`).
     This API does **not** require `ACCESS_FINE_LOCATION`, so the Android-10+ crash
     is gone. The system connects when the network is in range.
   - **API < 29** → unchanged legacy `WifiConfiguration` path.

2. **AndroidX migration.** `android.support.*` → `androidx.*`
   (`AppCompatActivity`, `RequiresApi`, etc.).

3. **Build modernization (so it compiles in 2026).**
   - AGP `3.3.1` → `8.5.2`
   - Gradle `4.10.1` → `8.9`
   - `jcenter()` (shut down) → `google()` + `mavenCentral()`
   - `compileSdk` / `targetSdk` `28` → `34`, `minSdk` → `21`
   - `appcompat-v7:28` → `androidx.appcompat:appcompat:1.7.0` + `androidx.core:core:1.13.1`

4. **Manifest.** Added `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`
   (used only by the legacy path's `getConfiguredNetworks`); the modern suggestion
   path does not need them. Added a launcher `intent-filter` for convenience.

## Build

### GitHub Actions (recommended)
`.github/workflows/build.yml` builds a **debug APK** on every push and on manual
dispatch (`workflow_dispatch`). The artifact is uploaded as
`adbjoinwifi-android10-debug`.

### Local
Requires JDK 17 + Android SDK (platform-34, build-tools 34.0.0):

```bash
gradle assembleDebug --no-daemon
# or: ./gradlew assembleDebug --no-daemon
```

## Usage (unchanged CLI)

```bash
adb shell am start -n com.steinwurf.adbjoinwifi/.MainActivity \
  -e ssid <SSID> -e password_type WPA -e password <PASSWORD>
```

Install with `adb install -r -g` so runtime permissions are auto-granted on
**API < 30**. On **API 30+** the `-g` flag is blocked by the OS, but the
`WifiNetworkSuggestion` path does not require it.

## Known limits

- `WifiNetworkSuggestion` is a *suggestion*: the OS decides when to connect
  (usually immediate when the AP is in range). It is not a forced, device-owner
  style connect.
- For deterministic, immediate connects in an ADB/rooted test environment,
  `adb shell cmd wifi connect-network <ssid> wpa2 <password>` (already used by the
  test tooling) is preferred on Android 10+.
- This fork does **not** bypass the device "USB install" developer switch; that
  must be enabled on the phone before `adb install` can succeed.
