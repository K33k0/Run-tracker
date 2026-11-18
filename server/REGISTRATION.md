# Activate RunZone's account connections

Hosting is ready at **https://runzone.wtechlab.uk**. These registrations are done once by RunZone's owner. People using the shared APK subsequently sign into their own accounts; they do not create developer apps or copy tokens.

## Strava

Open [Strava API settings](https://www.strava.com/settings/api) and register RunZone.

- Application name: `RunZone`
- Description: `Android running tracker with GPS routes, Bluetooth heart-rate monitoring and configurable heart-rate zone coaching. Users can optionally upload their own recorded runs to Strava.`
- Authorization callback domain: `runzone.wtechlab.uk` (hostname only).
- Redirect URI used by RunZone: `https://runzone.wtechlab.uk/oauth/callback/strava`
- Requested scope: `activity:write`.
- Check your API application's athlete capacity before sharing with additional people; Strava controls that limit and any increase/review.

Store the issued values in `STRAVA_CLIENT_ID` and `STRAVA_CLIENT_SECRET` on the server.

## Smashrun

Current builds use Smashrun user-level authentication with the public client ID `client`. No approval or RunZone server is needed for this mode. Smashrun recommends requesting a registered API key for mobile apps; the pending registration can be used later. See [Smashrun documentation](https://api.smashrun.com/v1/documentation).

- Description: Android GPS running tracker with Bluetooth heart-rate monitoring, configurable zones and user-authorized uploads of the user's own runs.
- Callback: `runzone://accounts/smashrun`
- Requested scope: `write_activity`.
- Request the mobile implicit flow (`response_type=token`). Smashrun sign-in and uploads happen directly on the phone, with no RunZone server. The default build uses the user-level `client` ID.

Build normally for user-level access, or override with `./gradlew -PsmashrunClientId=APPROVED_ID assembleDebug` once approved. The client ID is public. Do not put a client secret in the APK. Users sign in again when their token expires. Verify the custom callback above with a live user-level login before distributing. User-level access is limited to 250 requests/hour and requires reauthentication at most every 60 days (possibly sooner).

## Runalyze

Follow [Runalyze's public application API instructions](https://runalyze.com/doc-api) to request credentials for RunZone. Provide the app description, callback and logo when requested. The app icon source is `app/src/main/res/drawable/ic_launcher.xml`.

- Description: Android running tracker. Users record GPS and Bluetooth heart-rate data on their own phone and can opt into direct uploads of their own recordings to Runalyze.
- Callback: `https://runzone.wtechlab.uk/oauth/callback/runalyze`
- Requested scope: `activity_push`.

Store the issued values in `RUNALYZE_CLIENT_ID` and `RUNALYZE_CLIENT_SECRET`.

## Configure and verify

On the server, edit `$HOME/apps/runzone/shared/service.env` using your SSH editor. Keep the file owner-only (`600`). Do not put credentials in the Android app, the repository or chat.

For Strava and Runalyze, after entering server credentials:

```sh
systemctl --user restart runzone-accounts.service
curl --fail --silent https://runzone.wtechlab.uk/health
```

The provider's flag should change from `false` to `true`. The Android app refreshes availability when opened or when Settings is shown (30-second cache), so no new APK is required just to activate a provider. First test authorizing your own account and syncing a real saved run. Confirm its GPS, timing and heart rate on the destination service before distributing to more users.

**Smashrun approval is pending and live provider authorization/upload remains unverified.** Credentials, approval and each provider's user limits are external prerequisites; the hosting service is already running.
