# RunZone shared account connections

Smashrun now connects directly on Android and does not use this broker. Current builds use public client ID `client` with callback `runzone://accounts/smashrun`; optionally override with `-PsmashrunClientId=APPROVED_ID`; see [registration](REGISTRATION.md). The legacy Smashrun broker endpoints remain for older APKs. The broker setup below applies to Strava and Runalyze for current builds.

This is RunZone's OAuth service, shared by every installation of the app. Users sign in with their own Smashrun, Runalyze and Strava accounts. They never enter developer IDs, app secrets or personal API tokens into RunZone.

**Current status:** deployed on the existing server at `https://runzone.wtechlab.uk`, behind the existing Cloudflare Tunnel. Local and public HTTPS health checks pass. Provider applications have not yet been registered, and authorization/uploads are not tested against live accounts. The app reads the service's readiness flags and shows connections as pending until credentials are configured. History, recording, auto-pause and TCX exports work independently.

## Existing server deployment

- Service: `systemctl --user status runzone-accounts.service`
- Origin: `http://127.0.0.1:8787`, not exposed on a public server port.
- Application: `$HOME/apps/runzone/current` (release symlink).
- Private runtime: `$HOME/apps/runzone/runtime/node`, Node 24.22.3. Existing system Node/runtime installations are untouched.
- Provider credentials: `$HOME/apps/runzone/shared/service.env`, owner-only mode 600. Fill the six provider fields there; never paste secrets in chat. Restart with `systemctl --user restart runzone-accounts.service` after changes.
- Limits: 192 MB RAM, 25% CPU, 32 tasks. Automatic restart on failure and startup at boot through the existing lingering user manager.
- Public health endpoint: `https://runzone.wtechlab.uk/health`. `false` for a provider means its app credentials are not set yet, not that hosting is down.
- Published hostname: `runzone.wtechlab.uk`, routed through Cloudflare Tunnel to the loopback origin.

The release archive is checksum-verified before extraction. The deployment script is [deploy/install.sh](deploy/install.sh); it verifies the private runtime checksum, runs the OAuth tests on the server, installs a versioned release and checks local health before completion. It does not install global packages or need sudo. The systemd unit is [deploy/runzone-accounts.service](deploy/runzone-accounts.service).

To roll back code after a future release, read the saved previous target from `shared/previous-release`, repoint only RunZone's `current` symlink to that verified release path, and restart `runzone-accounts.service`. To disable this new service entirely, disable/stop that user service and remove only RunZone's hostname ingress/DNS record. The pre-change tunnel configuration is backed up locally under `.tools/tunnel-before-runzone.json`; never blindly restore that entire snapshot over newer unrelated changes.

## One-time setup for the app owner

1. Hosting is ready at `https://runzone.wtechlab.uk`.
2. Register one RunZone application with each provider. These credentials belong to RunZone, not to each runner:
   - **Strava:** create the application in [API settings](https://www.strava.com/settings/api). Set its callback domain to your account service's hostname. Review the application's athlete capacity before sharing more widely; capacity is controlled by Strava. Request only `activity:write`.
   - **Smashrun:** [request credentials for a mobile application](https://api.smashrun.com/v1/documentation). Register the callback below and request `write_activity`. Current Android builds default to user-level `client` authentication; a registered mobile app ID can override it.
   - **Runalyze:** [apply for the public application API](https://runalyze.com/doc-api), providing RunZone's description, callback URL and logo. Request `activity_push`.
3. Register these exact callbacks:
   - `https://runzone.wtechlab.uk/oauth/callback/strava`
   - `runzone://accounts/smashrun` (direct mobile flow; public ID supplied at build time)
   - `https://runzone.wtechlab.uk/oauth/callback/runalyze`
4. Configure environment variables from [.env.example](.env.example). Keep all client secrets on the server. Never commit `.env` or place a client secret in Gradle, the APK, the Android resources or a public URL.
5. Use Node 22+ and run `node --env-file=.env index.mjs`, behind an HTTPS reverse proxy to the loopback listener on port 8787. Ensure the configured `PUBLIC_URL` is the externally visible HTTPS origin. It must have no path/query/fragment. Use a process supervisor, restart on failure, disable request-body logging and redact OAuth callback query strings from proxy/access logs.
6. Build the app with that URL:

   ```sh
   ./gradlew -PoauthBaseUrl=https://runzone.wtechlab.uk assembleDebug
   ```

7. Open Settings in the app and connect each service. Complete an end-to-end test on a real account before distributing an account-enabled build. For broad distribution, create a release build with a stable signing key and complete each provider's app approval/branding requirements.

## Behavior and boundaries

- `/v1/oauth/start` creates a short-lived provider state bound to a proof challenge generated by that particular app installation.
- The provider sends its authorization code to the HTTPS server callback. The app secret is used only on the server to exchange the code.
- The browser returns to `runzone://accounts/{provider}` with a one-use ticket, never access or refresh tokens in the URL.
- `/v1/oauth/finish` releases tokens only to the app holding the original random verifier. Tickets expire after ten minutes. Wrong verifiers, replayed states and cross-provider callbacks are rejected.
- Android stores each user's access/refresh tokens encrypted with a key in that installation's Android Keystore. Recordings upload directly from the phone to the chosen provider. The broker does not receive run routes or heart-rate recordings.
- `/v1/oauth/refresh` exchanges that account's refresh token with the server-held app credentials and returns the rotated credentials to the phone. The broker keeps no enduring account database.
- Disconnect removes the phone's credentials and cancels pending uploads for that connection. Remote revocation is best effort; users can always revoke RunZone in their provider account's connected-app settings.
- Only **new** saved runs auto-sync. Historical runs upload when their owner explicitly chooses **Sync this run**. Queue entries are tied to a specific account-connection session, so reconnecting as another user does not send previously queued runs to the new account.
- Upload queues survive app restarts/reboots and use Android's network-constrained background jobs. Actual execution time depends on Android battery/network restrictions. Strava upload IDs are persisted before polling processing status. Runalyze/Smashrun uploads show **Sent** after HTTP acceptance; this does not claim the service has finished importing them.
- Turning automatic sync off pauses queued automatic uploads. A deliberate per-run upload remains possible. Remote services may detect duplicates differently after an interrupted network request; delivery is at least once, not a guarantee of exactly-once uploads.

The server uses bounded, ten-minute in-memory authorization transactions and must currently run as **one process/instance**. Restarting it cancels unfinished sign-ins, which users can retry; already connected accounts still refresh. For multiple instances, replace the state/ticket maps with shared, expiring storage and atomic ticket consumption. Configure production ingress rate limits; the built-in limit is per socket address and intentionally does not trust forwarded IP headers.

## Tests

```sh
node --test
```

Tests cover independent user sign-ins, proof binding, one-use tickets, forged/cross-provider state, transaction expiry, withheld upload scopes, token refresh and unconfigured providers. They use local mock token responses and do not create or modify remote activities.

Reference documentation: [Strava OAuth](https://developers.strava.com/docs/authentication/), [Strava uploads](https://developers.strava.com/docs/uploads/), [Smashrun API](https://api.smashrun.com/v1/documentation), [Runalyze app API](https://runalyze.com/doc-api), [Runalyze official upload example](https://github.com/Runalyze/personal-api/blob/main/bash/upload-activities.sh).
