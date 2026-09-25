# JaWa – Just Another Weather App

A small, ad-free Android weather app and home-screen widget for personal use.

- Follows you: town-level (coarse) location, place name shown on the widget
- Forecast from [Open-Meteo](https://open-meteo.com) (free, no API key)
- Works at 4×2 and 5×2: big current temperature plus the next 4–5 days
- Up to 10 saved places besides your current location; switch with ‹ › on the widget
- Tap the widget to open the full view (next 24 hours, 14-day forecast) for that place;
  swipe left/right there to change place. It refreshes at the same time
- Place search and forecasts from Open-Meteo; all places are fetched in one request
- Refreshes every ~30 minutes in the background
- No Google Play services required

## Getting the APK

- **Every push** to `main` runs the *Build* workflow, which only checks that the app
  builds. Its debug APK is kept for 14 days under Actions → the run → Artifacts.
- **Releases** happen only when you tag a version. Set `versionName` (and bump
  `versionCode`) in `app/build.gradle.kts`, commit, then:

  ```
  git tag v0.9
  git push origin main v0.9
  ```

  The *Release* workflow builds `JaWa-0.9.apk` (an APK, no AAB bundle) and publishes it
  on the repo's **Releases** page. Open it on your phone and install it (allow
  "install unknown apps" for your browser the first time).

### Signing

- **Release APKs** are signed with your own key, which the Release workflow takes from
  the repo's secrets (Settings → Secrets and variables → Actions → New repository secret):

  | Secret | Value |
  |---|---|
  | `KEYSTORE_BASE64` | your keystore file as base64: `base64 -w0 my-release.jks` (macOS: `base64 -i my-release.jks`) |
  | `KEYSTORE_PASSWORD` | the keystore password |
  | `KEY_ALIAS` | the key alias |
  | `KEY_PASSWORD` | the key password |

  Every release is signed with the same key, so new versions install over the old one.
- **Debug builds** (Android Studio, the Build workflow) use `app/debug.keystore` from the repo
  and install as a separate app, **JaWa Dev** (`com.jawa.app.debug`), so they never clash
  with the signed release.

## Building locally

Open the folder in Android Studio, or from a terminal run `./gradlew assembleDebug`
(`gradlew.bat assembleDebug` on Windows). The wrapper downloads the right Gradle
version (8.13) the first time. Build with JDK 21 (Android Studio's built-in JBR 21 is fine); the app code is compiled for Java 17. Targets Android 16 (API 36); runs on Android 8.0+.

## First run

1. Open the app; the first time it opens Settings. Tap **Allow location**.
2. Tap **Allow location all the time** so the widget can follow you while the app is closed.
3. If your phone kills background work (Xiaomi, Huawei, some Samsungs), use
   **Battery optimisation settings** and set JaWa to "not optimised".
4. Long-press the home screen → Widgets → JaWa.

## Project layout

- `WidgetRenderer.kt` — what the widget shows, resize thresholds
- `DetailActivity.kt`, `TempBarView.kt` — the 14-day detail screen
- `MainActivity.kt`, `PlacesAdapter.kt` — settings (places, location, battery, manual refresh)
- `Places.kt`, `Store.kt` — saved places and the on-device forecast cache
- `WeatherWorker.kt` — background refresh (location → forecast → redraw)
- `LocationHelper.kt`, `WeatherApi.kt` — location, Open-Meteo and place names
- `WeatherCodes.kt` — weather code → icon and text
- `tools/gen_icons.py` — generates the weather icons in `res/drawable/wx_*.xml`
- `tools/gen_widget_layout.py` — generates `res/layout/widget_weather.xml`

`app/debug.keystore` is only for debug builds; your release key never goes in the repo.

Weather data by Open-Meteo.com (CC BY 4.0). Place names from the phone's
geocoder or © OpenStreetMap contributors (Nominatim).
