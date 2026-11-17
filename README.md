# RunZone

Native Android running tracker (Android 8+), written in Java with platform APIs.

- GPS distance, elapsed time, pause/resume and local route recording.
- Bluetooth LE Heart Rate Service discovery and measurement notifications (8-bit and 16-bit values).
- Five editable heart-rate zone profiles, with a selected lower/upper target persisted between sessions.
- Distinct sound and vibration warnings after 5 seconds outside the target; repeats every 20 seconds. Boundaries are inclusive. Stale readings disappear after 10 seconds.
- Foreground tracking notification and local saved-run history; no RunZone account is required.
- Live heart-rate preview before and after runs, with the selected sensor remembered across app launches.
- Live OpenStreetMap street map with location marker, route trace, pan/pinch zoom, zoom buttons and recentering.
- Redesigned dark dashboard, lime accents, fixed run controls, dedicated Settings and History screens.
- Tap any saved run for its route map, active/elapsed time, distance, average pace, average/maximum heart rate and heart-rate chart. New recordings also include kilometre splits.
- TCX file save/share for Smashrun, Runalyze and Strava, preserving recorded GPS, timestamps, cumulative distance, heart rate and pause tracks.
- Optional pace-based auto-pause, off by default, with an adjustable threshold in Settings.
- Shared-account OAuth connection and background auto-sync implementation for Smashrun, Runalyze and Strava. The account service is deployed at `https://runzone.wtechlab.uk`. **Provider registration is still pending; the app shows those connections as unavailable until their credentials are configured.**

## Build

Open this folder in Android Studio with Java 17 and Android SDK 35. The Gradle wrapper uses Gradle 8.11.1. Local verification tools are also available under the ignored `.tools` directory in this workspace.

```sh
./gradlew testDebugUnitTest assembleDebug
```

The GitHub Actions workflow runs those checks and uploads a debug APK when this project is pushed to GitHub.

## Use

Set your target range under Settings → Heart-rate zones. Wake your Bluetooth sensor and select it using the Sensor button; live BPM appears immediately, without starting a run. Connecting a sensor on Android 12+ asks only for Nearby devices permission, independently of location. The sensor stays connected after finishing a run. Preview readings never trigger coaching alerts.

Tap the map's recenter button to allow location and see your position before starting a run. The map displays your recorded route during a run; dragging stops automatic following until you recenter. Map tiles require internet access and use the OpenStreetMap service with a distinct User-Agent, visible attribution and a seven-day disk cache; only visible tiles are requested. GPS recording still works without internet. Location settings and app permission controls are under Settings.

Start run begins recording and zone coaching. Keep media volume audible for tones. Pause stops distance accumulation and alerts, while your location and heart rate remain live. Finish saves the run and stops route recording. GPS can be used without a sensor.

Open History and tap a run to view its route and stats. Scroll to Sync & Export to save or share a TCX file. Import it using [Smashrun's file import](https://smashrun.com/faq/basics), [Runalyze's activity upload](https://runalyze.com/activity/new-upload) or [Strava's file uploader](https://www.strava.com/upload/select). Files with no GPS may not import on Smashrun; missing sensor data is omitted rather than invented. TCX requires a Calories field; it is set to zero because RunZone does not measure calories.

In Settings, enable Auto-pause and set the **Pause when slower than** pace (3:00–30:00 min/km, default 12:00). Sustained reliable GPS speed below this threshold pauses recording after ten seconds. Movement at least 0.25 m/s above the speed threshold resumes after three seconds. Poor/stale GPS never counts as standing still. Manual pauses require manual resume. Auto-pause excludes paused time, route movement and heart-rate samples from the active recording.

Run files are private JSON under the application's `files/runs` directory. New version-2 records include actual start/end times, cumulative distance, active time per point, independent heart-rate samples, starting zone limits and separate pause tracks. Earlier records remain readable and exportable; their start time is estimated and kilometre splits are unavailable. Heart-rate averages use the valid recorded samples. Uninstalling clears these files. Disconnected sensors require manual reconnection through the sensor picker. Runs are not recovered after process termination or force-stop.

## Shared app accounts and automatic sync

The app can be shared without asking each user for developer credentials. The current implementation uses a small OAuth account service to keep RunZone's shared application secrets outside the APK; recordings upload directly from the phone. This is an architectural choice for authentication, not a requirement for run recording or file exports. See [the account service setup guide](server/README.md).

The hosted account service is configured in `gradle.properties`. Once the app owner registers RunZone with the providers and sets their credentials on the server, users select **Connect account** and sign in with their own provider account. New saved runs queue for automatic upload; users can turn this off or disconnect. Old runs are never bulk-uploaded automatically. Queue entries are tied to the connection that created them to avoid sending another user's queued runs after switching accounts. Tokens are encrypted with Android Keystore. No app client secrets are bundled in the APK. The app fetches provider readiness from the account service, so enabling a provider does not require another APK build. Pending connections are shown clearly, and file exports remain available.

## Verification status

The debug APK builds against SDK 35. All 21 Android unit tests pass, covering heart-rate decoding/coaching, map projection, slow-pace distance accumulation, legacy recording compatibility, splits, TCX output and auto-pause. Android lint passes with nonblocking compatibility/localization warnings. Both a test export and a TCX exported from an actual older run on the phone were separately validated against Garmin's official TrainingCenterDatabase v2 XML schema. The account broker has seven passing mock-provider tests for independent users, state/proof validation, expiry, token refresh and transient failures.

On a Moto g84 5G, verified installation and launch, live OpenStreetMap/current-location display, live BPM from an HR8 sensor without a running timer, History navigation and viewing an actual older recording with its route, pace, distance and duration. The sensor-only monitor uses the connected-device foreground service type. Account-service tests also pass on the existing server, and its public HTTPS health endpoint is reachable. Live third-party authorization/uploads are **not** verified or available yet: provider app credentials have not been registered. CI runs the Android build, tests, lint and server tests on pushes and pull requests. Outdoor route accuracy, automatic pause/resume and screen-off running still need physical validation.

Before release, verify on a physical Android phone:

1. Permission denial, approximate-location-only grants, Bluetooth off and GPS off.
2. Real BLE strap discovery, notification subscription, disconnect and reconnect.
3. Both target boundaries, five-second alert delay, repetition, pause and stale readings.
4. Outdoor distance and route, screen-off tracking, save, app reopen and history.
5. System process termination and battery restrictions; active runs currently end if the process is killed.

Android foreground-service and GATT implementation references: [service types](https://developer.android.com/develop/background-work/services/fgs/service-types), [BluetoothGatt](https://developer.android.com/reference/android/bluetooth/BluetoothGatt).

## Public source and secrets

See [SECURITY.md](SECURITY.md) for secret-handling rules. Real credentials, signing keys, recordings, device screenshots and local deployment backups are excluded. The account-service URL is public by design.
