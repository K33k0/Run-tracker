# Development

RunZone is a native Android app written in Java, using Android platform APIs. The optional account service is a Node.js OAuth broker; it does not store recorded runs.

## Build and test

Requirements: Java 17, Android SDK 35, Python 3 and Node.js 22+. The Gradle wrapper uses Gradle 8.11.1.

```sh
./gradlew testDebugUnitTest assembleDebug lintDebug
node --test server/broker.test.mjs
python3 -m unittest discover -s scripts -p 'test_*.py'
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. For signed production builds and automated releases, see [RELEASING.md](../RELEASING.md). Debug and production APKs use different signing certificates.

## Project layout

| Path | Responsibility |
| --- | --- |
| `app/src/main/java/com/runzone/MainActivity.java` | Run dashboard, settings, history, exports and account UI |
| `app/src/main/java/com/runzone/RunService.java` | Foreground GPS recording and independent Bluetooth heart-rate monitoring |
| `app/src/main/java/com/runzone/ZoneCoach.java` | Zone-alert timing and suppression during preview/pause |
| `app/src/main/java/com/runzone/AutoPause.java` | Pace threshold, pause/resume delay and hysteresis |
| `app/src/main/java/com/runzone/RunRecord.java` | Saved-run compatibility, heart-rate stats and splits |
| `app/src/main/java/com/runzone/TcxExporter.java` | TCX v2 serialization |
| `app/src/main/java/com/runzone/CloudSync.java` | Durable, account-bound background upload queue |
| `server/` | Shared OAuth broker and deployment/registration documentation |
| `scripts/` | Release versioning and APK verification |

## Recording and sensors

Location updates are filtered for accuracy, age and implausible speed. A distance anchor retains sub-two-metre fixes so frequent updates do not discard slow movement. Manual and automatic pauses reset the distance anchor and separate route tracks. Active duration excludes pauses; elapsed duration includes them.

Bluetooth devices must expose the standard BLE Heart Rate Service. Measurements support both 8-bit and 16-bit heart-rate payloads and sensor-contact flags. Stale readings disappear after ten seconds. GPS works independently of a heart-rate monitor. A sensor-only foreground service can show live BPM before a run; zone coaching is enabled during a run, including automatic pauses, and suppressed during manual pauses. Dropped connections and failed subscriptions retry after five seconds; thirty seconds without measurements also triggers reconnection. Explicit disconnect cancels retries.

Zone warnings trigger after five seconds continuously outside the inclusive target boundaries, then repeat every twenty seconds. Sound and vibration patterns differ above/below the target.

Auto-pause is optional. The pace range is 3:00–30:00 min/km (default 12:00). Reliable speed below the threshold for ten seconds pauses recording. Speed at or above the threshold for three seconds resumes. Accurate position displacement can supply resume speed when GPS omits its speed field. Manual pauses are never automatically resumed. Stale/poor GPS does not count as stationary movement. A partial wake lock is held for the run (including pauses) and released on finish or service destruction so screen-off processing can continue.

## Storage and exports

Run files are private JSON under the app's `files/runs` directory. Version-2 records include actual start/end times, cumulative distance and active time per route point, independent heart-rate samples, starting zone limits and track identifiers for pauses. Earlier recordings remain readable/exportable; their start time is estimated and kilometre splits are unavailable. Heart-rate averages use valid recorded samples.

TCX exports contain timestamps, measured distance, available GPS/altitude/heart rate and separate pause tracks. Missing measurements are omitted. TCX requires a Calories field; RunZone writes zero because it does not estimate calories. The Android document picker saves user-chosen files. Sharing uses a read-only, per-URI-grant content provider rather than exposing private storage.

## Maps and network access

The native slippy map requests only visible OpenStreetMap tiles. It sends an application-specific User-Agent, shows attribution and keeps a seven-day disk cache. Bulk/offline prefetch is not implemented. GPS recording does not require internet access; uncached street-map tiles do.

Smashrun connects directly from the phone using its mobile implicit OAuth flow. The default public client ID is `client` (user-level authentication); optionally override it with `-PsmashrunClientId=APPROVED_ID`. The callback is `runzone://accounts/smashrun`. Smashrun recommends a registered API key for mobile apps. User-level authentication is limited to 250 requests/hour and requires reauthentication after at most 60 days (possibly sooner). Only the public app ID goes into the APK; no client secret or account server is used for Smashrun. Browser callbacks are bound to a random, single-use state that expires after ten minutes. Access tokens are stored with Android Keystore encryption. Expired or rejected access asks the user to sign in again; no refresh token is available. Reconnecting creates a new account session, so older queued runs must be explicitly sent again to avoid uploading them to a different account.

For the other providers, `oauthBaseUrl` in `gradle.properties` points to the account broker and can be overridden with `-PoauthBaseUrl=https://your-service.example`. Each phone stores its own provider tokens encrypted with Android Keystore. Client secrets belong only on the server. The app uploads recordings directly to the selected provider; the broker handles authorization and token refresh only. See the [server guide](../server/README.md) and [provider registration guide](../server/REGISTRATION.md).

## Validation and remaining checks

Unit tests cover heart-rate decoding, alert boundaries/timing, stale measurements, preview suppression, map projection, auto-pause, slow-pace distance accumulation, legacy recordings, splits and TCX output. Broker tests use mock providers to check state/proof binding, independent users, expiry, scope rejection and token refresh. Release-tool tests reject invalid versions, debug APKs and unexpected signing certificates.

TCX output from a test fixture and an actual older recording has been validated separately against Garmin's official TrainingCenterDatabase v2 schema. A physical Android phone has been used to verify installation, live BPM without a run, map/current-location rendering, saved-run details and export generation.

Outdoor distance accuracy, automatic pause/resume and screen-off runs still need further physical validation. Live third-party authorization/uploads remain unverified while provider registrations are pending. Interrupted recordings are not recovered after process termination or force-stop.

References: [foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types), [BluetoothGatt](https://developer.android.com/reference/android/bluetooth/BluetoothGatt), [OpenStreetMap tile usage](https://operations.osmfoundation.org/policies/tiles/).
