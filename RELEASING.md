# Automatic Android releases

Push a tag such as `v1.2.0` to publish a signed Android APK:

```sh
git tag -a v1.2.1 -m "RunZone v1.2.1"
git push origin v1.2.1
```

Use a new, increasing `vMAJOR.MINOR.PATCH` tag for each release. Pushing a normal branch builds/tests the app but does not publish a release. The Release workflow can also be manually run **from an existing version tag**, not from `main`.

## Pipeline

1. Validate the version tag and derive Android version metadata. `v1.2.0` becomes version name `1.2.0` and code `1002000`. Minor/patch numbers must be below 1000; the code must fit Android's version-code limit.
2. Run the reusable Android build/tests/lint/server checks, release-tool tests and full-history secret scan. These jobs receive no signing credentials.
3. Restore the private release key from encrypted GitHub Actions secrets into the runner's temporary directory.
4. Build a non-debuggable release APK and run release lint.
5. Verify the APK signature against the pinned public certificate fingerprint, package name, version name/code and non-debuggable flag.
6. Upload the APK, SHA-256 checksum and public certificate fingerprint to a draft GitHub Release. Publish only after all assets upload successfully. A rerun may complete an unpublished draft, but cannot replace an already published release.
7. Delete the temporary private signing key from the runner.

Only the publishing job has repository write permission. Pull requests cannot trigger a release or access signing secrets. A tag must reference a commit on `main` containing the Release workflow and matching reusable workflows. The signing job does not write Gradle caches.

## Signing and recovery

The permanent PKCS#12 key is supplied through these encrypted repository secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The repository contains **only** the public certificate SHA-256 fingerprint in [signing/release-certificate.sha256](signing/release-certificate.sha256). It cannot be used to sign an app.

The original key and recovery information are retained in the owner-only local directory `.tools/release-signing/`, which is ignored by Git. Back up that private directory securely, separately from the repository. GitHub secrets cannot be downloaded again. Keep this signing key for future releases: replacing it prevents existing release installations from accepting normal updates.

For local production builds, provide `RUNZONE_KEYSTORE_PATH`, `RUNZONE_STORE_PASSWORD`, `RUNZONE_KEY_ALIAS` and `RUNZONE_KEY_PASSWORD` as private environment variables, then run:

```sh
./gradlew assembleRelease -PreleaseVersion=1.2.0 -PreleaseVersionCode=1002000
```

Release builds fail if signing configuration is missing. Never commit the keystore, its recovery data, passwords or base64-encoded private key.

## Installing releases

Download `RunZone-vX.Y.Z.apk` from the GitHub Release. `SHA256SUMS` verifies the downloaded file; `signing-certificate.sha256` identifies the permanent release signer. Future release APKs use the same signer and increasing version codes.

Earlier development/debug installations use a different certificate and cannot be updated directly with the production APK. Their data remains on the phone while those installations remain installed. A one-time migration needs a data backup/restore plan; uninstalling the debug app erases its private recordings/settings. The release workflow does not uninstall or modify the app on a phone.

This publishes APKs to GitHub Releases, not Google Play. Account-provider registrations remain a separate setup step.
