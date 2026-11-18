# RunZone

![RunZone — Find your rhythm. GPS runs, Bluetooth heart rate, your zones.](docs/assets/runzone-banner.svg)

[![Android build](https://github.com/K33k0/Run-tracker/actions/workflows/android.yml/badge.svg)](https://github.com/K33k0/Run-tracker/actions/workflows/android.yml)
[![Secret scan](https://github.com/K33k0/Run-tracker/actions/workflows/secrets.yml/badge.svg)](https://github.com/K33k0/Run-tracker/actions/workflows/secrets.yml)
[![Release](https://github.com/K33k0/Run-tracker/actions/workflows/release.yml/badge.svg)](https://github.com/K33k0/Run-tracker/actions/workflows/release.yml)

**An Android running tracker built around your heart-rate zones.**

Set the effort you want to run at. RunZone tracks your route, reads your Bluetooth heart-rate monitor and gives you sound and vibration warnings when you stay above or below your target.

[**Download the latest APK**](https://github.com/K33k0/Run-tracker/releases/latest) · [All releases](https://github.com/K33k0/Run-tracker/releases) · [Report an issue](https://github.com/K33k0/Run-tracker/issues)

> **Early release:** GPS tracking, live heart rate, zone alerts, run history and TCX exports are available. Direct account syncing is still awaiting provider setup. Further outdoor and screen-off testing is ongoing.

## What it does

| Feature | What you get |
| --- | --- |
| **Heart-rate zone coaching** | Five adjoining zones with shared editable boundaries and a clearly marked target; sound/vibration warnings above and below your active range. |
| **Live BPM before a run** | Connect your Bluetooth sensor and see your heart rate immediately. No running timer needed. |
| **GPS tracking and a live map** | Route recording, distance, duration and average pace, with street maps, zoom and recenter controls. |
| **Distance target** | Set an optional kilometre target from the Run screen or Settings. A one-time sound and three long vibrations mark reaching it; recording continues. |
| **Optional auto-pause** | Choose a pace threshold. Recording pauses after sustained slower movement and resumes when you speed up. |
| **Run history** | Open a saved run for its route, active/elapsed time, pace, heart-rate stats and chart. New recordings include kilometre splits. |
| **TCX export** | Save or share a workout file for Smashrun, Runalyze, Strava or another compatible service. |

## Install

1. Open the [latest GitHub Release](https://github.com/K33k0/Run-tracker/releases/latest).
2. Download the `RunZone-vX.Y.Z.apk` file to your phone.
3. Open it and allow installation from your browser/file manager if Android asks.

**Requirements:** Android 8.0 or newer. A Bluetooth heart-rate monitor is optional; GPS recording works on its own. Sensors must broadcast the standard Bluetooth LE Heart Rate Service. A smartwatch needs a compatible heart-rate broadcast mode to act as a sensor.

Release APKs use a permanent signing certificate. Each release includes a SHA-256 checksum and the public signing-certificate fingerprint.

<details>
<summary>Already using a development/debug build?</summary>

Development builds and production releases use different signing certificates, so Android cannot install one directly over the other. Export runs you want to keep before removing a development build. **Uninstalling clears its private recordings and settings.** Importing those exported files back into RunZone's History is not currently supported.

The release workflow never uninstalls or changes the app on your phone. See [release and signing details](RELEASING.md).

</details>

## Your first run

1. **Set your target:** open **Settings → Heart-rate zones**, choose your target and edit the four shared upper boundaries. All five ranges appear together and cover 30–240 BPM without gaps or overlaps. You can also tap the target on the Run screen.
2. **Connect your sensor:** wear/wake your monitor, tap **Sensor** on the Run screen and select it. Live BPM should appear before you start.
3. **Allow location:** use the map's recenter button and grant location permission. Precise location is needed for run recording; Bluetooth connection asks for Nearby devices permission on newer Android versions.
4. **Start running:** tap **Start run**. Pause/resume whenever you need to, then **Finish → Save run**, or choose **Discard run** and confirm to stop without saving.
5. **Check screen-off readiness:** Settings shows background restrictions, battery optimisation, phone Battery Saver and notification status. Starting a run also prompts when these checks need attention, with an option to start anyway. Set RunZone battery usage to Unrestricted / Don’t optimise; manufacturer sleeping-app and auto-start restrictions still need a manual check.
6. **Review it:** open **History**, select your run and scroll to **Sync & Export** to save or share a TCX file.

Keep media volume audible for the warning tones. Zone and location controls live in Settings, so the Run screen stays focused on your workout.

### How the zone warnings work

Your lower and upper limits are inclusive. The selected zone is named on the Run screen and highlighted on the five-zone bar. Zone changes are locked until the current run finishes or is discarded. Older independent profiles are converted to shared boundaries, preserving the selected BPM range where possible (its zone number may change); otherwise the starting ranges are restored. Review your target after upgrading. After five continuous seconds outside them, RunZone sounds and vibrates a warning, repeating every twenty seconds while you remain outside the target. Previewing your BPM or pausing a run does not trigger coaching alerts. A sensor reading that is more than ten seconds old is shown as missing.

**Settings → BPM-paced zone beeps** is off by default. Enable it for short, distinct above/below-target tones paced to your current BPM. These replace the periodic warning sound while enabled; vibration reminders remain. Beeps stop immediately on an in-range reading, manual pause, missing sensor data, or disabling the option. Cadence follows reported BPM, rather than individual detected heartbeats.

### Distance targets and stronger vibrations

Tap the distance target on the Run screen (or **Settings → Distance target**). Choose 5 km, 10 km, 21.1 km or enter 0.01–1000 km. **No target** clears it. The target is reused for future runs and stays fixed during an active run. The Run screen shows the remaining distance and confirms when the target is reached.

Crossing the target using recorded GPS distance plays a confirmation tone and three one-second vibration pulses once. Recording continues until you finish or discard the run. Paused movement does not count. Target feedback briefly takes priority over zone alerts and BPM-paced beeps.

Zone vibrations now use three separated 500 ms pulses below target or 800 ms pulses above target, requesting maximum amplitude where supported. **Settings → Test strong vibrations** previews each pattern before a run. Phone hardware and system vibration settings still determine the physical strength; test with your usual phone placement. Sound uses media volume.

### Auto-pause

Auto-pause is **off by default**. Enable it in Settings and choose **Pause when slower than**; the default is 12:00 min/km.

RunZone waits ten seconds below the speed threshold before pausing and three seconds clearly above it before resuming. Poor or lost GPS does not count as standing still. A manual pause always needs a manual resume.

## Smashrun, Runalyze and Strava

| Service | TCX file export | Direct account sync |
| --- | --- | --- |
| Smashrun | Available | Provider setup pending |
| Runalyze | Available | Provider setup pending |
| Strava | Available | Provider setup pending |

TCX files preserve recorded timing, distance, GPS and heart rate when available. Missing sensor data is left out. Runs without GPS may not import into Smashrun.

Use the service's file importer: [Smashrun](https://smashrun.com/faq/basics), [Runalyze](https://runalyze.com/activity/new-upload) or [Strava](https://www.strava.com/upload/select).

The account-connection and background-sync code is included, but provider registrations are not complete yet. **Direct sync is not currently available.** Once enabled, users will sign into their own accounts; new saved runs can sync automatically, while older runs upload only when their owner chooses. App owners can find setup instructions in the [account service guide](server/README.md).

## Your data

- **No RunZone account is needed** to record, review or export runs.
- Routes and heart-rate recordings are stored in the app's private storage on your phone. Uninstalling removes them, so export anything you want to keep.
- Street maps come from [OpenStreetMap](https://www.openstreetmap.org/copyright). Uncached map tiles need internet access; GPS recording does not. Map requests share the requested map area and your network connection with the tile provider.
- Account connections are optional. When enabled, recordings upload directly to the selected service. The shared account service handles sign-in and token refresh; it does not receive run recordings.
- Provider tokens are encrypted on the phone with Android Keystore. App secrets and signing keys are excluded from this repository. See [SECURITY.md](SECURITY.md).

## Current limitations

This is an early version. Sensor disconnections need manual reconnection, and an active run is not recovered if Android terminates the process or the app is force-stopped. Older recordings show the data that was saved at the time; their start time is estimated and kilometre splits are unavailable. Outdoor GPS accuracy, automatic pause/resume and screen-off behavior need more real-run validation.

## Build and contribute

Use Android Studio or Java 17 with Android SDK 35:

```sh
./gradlew testDebugUnitTest assembleDebug lintDebug
```

For the account-service and release-tool tests:

```sh
node --test server/broker.test.mjs
python3 -m unittest discover -s scripts -p 'test_*.py'
```

[Development guide](docs/DEVELOPMENT.md) · [Automatic releases](RELEASING.md) · [Account service](server/README.md) · [Provider registration](server/REGISTRATION.md) · [Security](SECURITY.md)

Found a problem? [Open an issue](https://github.com/K33k0/Run-tracker/issues) with your Android version, app version, sensor model if relevant, and steps to reproduce it. Leave out account tokens, private routes and other personal data.
