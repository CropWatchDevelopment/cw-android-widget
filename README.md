# CropWatch Android Widget

A native Android **home-screen widget** for [CropWatch](https://app.cropwatch.io) — fleet status at a glance plus a scrollable list of your devices, pulling live data from the CropWatch API with a native sign-in.

| Signed out | Signed in |
|---|---|
| Lock + **Sign in** button (opens a native login screen) | Online / Offline / Alerts pills, a gateways line, and a vertical, finger-scroll list of device cards |

**Features**
- Native email/password sign-in against `POST /v1/auth/login`; credentials cached in the **Android Keystore-encrypted** store for **silent re-login** when the short-lived token expires.
- Live data from `GET /v1/dashboard/devices` (+ `GET /v1/gateway`): per-device location, name, primary/secondary readings, last-seen, and a status stripe.
- **Status stripe** per device: `online` (green), `alert` (red + badge, from `error_status`), `stale` (red — has reported before but overdue), `never` (gray — no uplink yet).
- **Location** and **group** filters (header icons) that re-query the API server-side.
- **Dark + light** themes that follow the system, ported from the CropWatch design-system tokens.
- **Offline detection**: keeps the last data and shows a "No connection" indicator instead of logging you out.
- Tapping a device row opens it on `app.cropwatch.io`; the gateways row opens `/gateways`.

## Requirements

- **JDK 17 (a full JDK, with `jlink`)** — the Android `JdkImageTransform` requires `jlink`, so a JRE-only install will fail. [Temurin 17](https://adoptium.net/) works well.
- **Android SDK** with `platforms;android-34` and `build-tools;34.0.0` (install via Android Studio or `sdkmanager`).
- A device (or emulator) on **Android 8.0+ (API 26)** with USB debugging enabled.

## Setup

1. **Point Gradle at the SDK** — create `local.properties` (gitignored):
   ```properties
   sdk.dir=/absolute/path/to/Android/Sdk
   ```
2. **If your default `java` is JRE-only** (no `jlink`), point Gradle at a full JDK 17 via your **user-level** Gradle config (`~/.gradle/gradle.properties`, *not* committed):
   ```properties
   org.gradle.java.home=/absolute/path/to/jdk-17
   ```
   If your `JAVA_HOME` already points at a full JDK 17, you can skip this.

## Build & install

```bash
# Build the debug APK
./gradlew :app:assembleDebug

# Install on a connected device (grant runtime permissions)
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
```

Then **add the widget**: long-press the home screen → **Widgets** → **CropWatch** → drag it on (designed for a 4×3 cell footprint, resizable). Tap **Sign in** and log in with your CropWatch account.

## Architecture

- **`CropWatchWidgetProvider`** (`AppWidgetProvider`) builds the `RemoteViews`, wires click intents, and runs background refreshes (`goAsync` + executor).
- **`DeviceListRemoteViewsService`** feeds the device `ListView` (one `RemoteViews` row per device).
- **`WidgetRepository`** fetches + reduces the API response into a compact cached JSON payload (so rendering never blocks on the network), and owns the silent-re-login / offline logic.
- **`Api`** — thin REST client over `HttpURLConnection` + `org.json` (no third-party networking deps).
- **`Session`** (plain prefs: token, email, cached payload, filters) and **`Credentials`** (EncryptedSharedPreferences: email + password).
- **`LoginActivity`** — native sign-in; doubles as the account/settings screen with sign-out.
- **`FilterActivity`** — location/group picker dialog.

### Widget-platform notes
App widgets render through `RemoteViews`, which is restrictive:
- There is **no horizontal-scrolling list** — the device "carousel" is a vertical, finger-scroll `ListView`.
- `setClipToOutline` is **not** a remotable method, so the rounded status stripe is **baked into each card's layer-list background** (`tile_bg_*`) and swapped per status, rather than clipped at runtime.
- Icons are Material Symbols / the CropWatch logo converted to `VectorDrawable`s.

## API

Base URL: `https://api.cropwatch.io` (all endpoints under `/v1`, Bearer JWT). Key endpoints used: `auth/login`, `dashboard/devices`, `gateway`. Tokens are short-lived with no refresh token, which is why credentials are stored for silent re-authentication.
